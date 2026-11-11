// NSO loading, relocation, and symbol resolution.
#include <stdlib.h>
#include <string.h>
#ifdef CLUSTERTRUCK_GENERIC_TITLE
#include <dirent.h>
#include <sys/stat.h>
#endif

#include "rt.h"

Module g_modules[4];
int g_nmodules;
#ifdef CLUSTERTRUCK_GENERIC_TITLE
static unsigned g_missing_import_logs;
#endif

enum { DT_NULL = 0, DT_PLTRELSZ = 2, DT_HASH = 4, DT_STRTAB = 5, DT_SYMTAB = 6, DT_RELA = 7, DT_RELASZ = 8,
       DT_JMPREL = 23, DT_INIT_ARRAY = 25, DT_FINI_ARRAY = 26, DT_INIT_ARRAYSZ = 27, DT_FINI_ARRAYSZ = 28,
       DT_RELRSZ = 35, DT_RELR = 36, DT_GNU_HASH = 0x6FFFFEF5 };
enum { R_ABS64 = 257, R_GLOB_DAT = 1025, R_JUMP_SLOT = 1026, R_RELATIVE = 1027 };

typedef struct { uint32_t name; uint8_t info, other; uint16_t shndx; uint64_t value, size; } Elf64Sym;
typedef struct { uint64_t off, info; int64_t addend; } Elf64Rela;

#define MAX_DYNSYM_COUNT 1000000ull

static bool module_contains(Module *m, uint64_t offset, uint64_t length) {
    return offset <= m->size && length <= m->size - offset;
}

// Adapted from the MIT-licensed SavageWindRecomp loader's DT_GNU_HASH handling.
// Additional bounds checks prevent malformed hash chains from escaping the mapped NSO image.
static uint64_t gnu_hash_nsyms(Module *m, uint64_t hash) {
    if (hash < m->base) FATAL("%s: GNU hash table precedes its module\\n", m->name);
    uint64_t h = hash - m->base;
    if (!module_contains(m, h, 16)) FATAL("%s: truncated GNU hash header\\n", m->name);
    uint32_t nbuckets = *(uint32_t *)(m->base + h);
    uint32_t symoffset = *(uint32_t *)(m->base + h + 4);
    uint32_t bloom_size = *(uint32_t *)(m->base + h + 8);
    if (!nbuckets || nbuckets > MAX_DYNSYM_COUNT || symoffset > MAX_DYNSYM_COUNT ||
        bloom_size > MAX_DYNSYM_COUNT)
        FATAL("%s: unreasonable GNU hash dimensions\\n", m->name);
    uint64_t bloom_bytes = (uint64_t)bloom_size * 8;
    if (!module_contains(m, h + 16, bloom_bytes))
        FATAL("%s: GNU hash bloom filter is outside the module\\n", m->name);
    uint64_t buckets = h + 16 + bloom_bytes;
    uint64_t bucket_bytes = (uint64_t)nbuckets * 4;
    if (!module_contains(m, buckets, bucket_bytes))
        FATAL("%s: GNU hash buckets are outside the module\\n", m->name);
    uint64_t chain = buckets + bucket_bytes;
    uint32_t last = 0;
    for (uint32_t i = 0; i < nbuckets; i++) {
        uint32_t value = *(uint32_t *)(m->base + buckets + (uint64_t)i * 4);
        if (value && value < symoffset) FATAL("%s: invalid GNU hash bucket index\\n", m->name);
        if (value > last) last = value;
    }
    if (last < symoffset) return symoffset;
    for (uint64_t index = last; index < MAX_DYNSYM_COUNT; index++) {
        uint64_t chain_off = chain + (index - symoffset) * 4;
        if (!module_contains(m, chain_off, 4))
            FATAL("%s: GNU hash chain has no terminating entry\\n", m->name);
        uint32_t value = *(uint32_t *)(m->base + chain_off);
        if (value & 1) return index + 1;
    }
    FATAL("%s: GNU hash chain exceeds the symbol limit\\n", m->name);
}

// DT_RELR packs relative relocations: even entries name one target; odd entries are
// bitmaps for the next 63 pointer-sized targets. The addend is already stored at each target.
static uint64_t relocate_relr(Module *m, uint64_t table, uint64_t size) {
    if (table < m->base || size % 8) FATAL("%s: invalid DT_RELR table\\n", m->name);
    uint64_t table_off = table - m->base;
    if (!module_contains(m, table_off, size)) FATAL("%s: DT_RELR table is outside the module\\n", m->name);
    uint64_t cursor = 0, count = 0;
    bool cursor_valid = false;
    for (uint64_t i = 0; i < size / 8; i++) {
        uint64_t entry = ((uint64_t *)(m->base + table_off))[i];
        if (!(entry & 1)) {
            if ((entry & 7) || !module_contains(m, entry, 8))
                FATAL("%s: DT_RELR target is outside the module or unaligned\\n", m->name);
            *(uint64_t *)(m->base + entry) += m->base;
            if (entry > UINT64_MAX - 8) FATAL("%s: DT_RELR cursor overflow\\n", m->name);
            cursor = entry + 8;
            cursor_valid = true;
            count++;
        } else {
            if (!cursor_valid) FATAL("%s: DT_RELR bitmap precedes an address\\n", m->name);
            for (int bit = 0; bit < 63; bit++) {
                if (!((entry >> (bit + 1)) & 1)) continue;
                uint64_t delta = (uint64_t)bit * 8;
                if (cursor > m->size || delta > m->size - cursor ||
                    !module_contains(m, cursor + delta, 8))
                    FATAL("%s: DT_RELR bitmap target is outside the module\\n", m->name);
                *(uint64_t *)(m->base + cursor + delta) += m->base;
                count++;
            }
            if (cursor > UINT64_MAX - 63 * 8) FATAL("%s: DT_RELR cursor overflow\\n", m->name);
            cursor += 63 * 8;
        }
    }
    return count;
}

static size_t lz4_decompress(const uint8_t *src, size_t srclen, uint8_t *dst, size_t dstlen) {
    const uint8_t *s = src, *se = src + srclen;
    uint8_t *d = dst, *de = dst + dstlen;
    while (s < se) {
        uint8_t tok = *s++;
        size_t lit = tok >> 4;
        if (lit == 15) { uint8_t b; do { b = *s++; lit += b; } while (b == 255); }
        if (d + lit > de || s + lit > se) break;
        memcpy(d, s, lit);
        d += lit; s += lit;
        if (s >= se) break;
        size_t off = s[0] | (s[1] << 8);
        s += 2;
        size_t ml = tok & 15;
        if (ml == 15) { uint8_t b; do { b = *s++; ml += b; } while (b == 255); }
        ml += 4;
        uint8_t *m = d - off;
        if (m < dst || d + ml > de) break;
        while (ml--) *d++ = *m++;
    }
    return d - dst;
}

static uint8_t *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    *len = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *p = malloc(*len);
    fread(p, 1, *len, f);
    fclose(f);
    return p;
}

#ifdef __ANDROID__
// (runtime/nvn/shader_prep.c: the main module's own shaders, in its data segments)
size_t rt_lz4_decompress(const uint8_t *src, size_t srclen, uint8_t *dst, size_t dstlen) {
    return lz4_decompress(src, srclen, dst, dstlen);
}
#endif

static void load_nso(Module *m, const char *path, uint64_t base) {
    size_t len;
    uint8_t *raw = read_file(path, &len);
    if (!raw || memcmp(raw, "NSO0", 4)) FATAL("cannot load %s\n", path);
    uint32_t flags = *(uint32_t *)(raw + 0xC);
    uint32_t seg[3][3];  // file_off, mem_off, size
    for (int i = 0; i < 3; i++) memcpy(seg[i], raw + 0x10 + i * 0x10, 12);
    uint32_t bss = *(uint32_t *)(raw + 0x3C);
    uint64_t size = (seg[2][1] + seg[2][2] + bss + 0x3FFF) & ~0x3FFFull;
    plat_commit(base, size);  // inside the guest window: zero-filled, read/write
    for (int i = 0; i < 3; i++) {
        uint32_t csize = *(uint32_t *)(raw + 0x60 + i * 4);
        uint8_t *dst = (uint8_t *)base + seg[i][1];
        if (flags & (1 << i)) {
            size_t got = lz4_decompress(raw + seg[i][0], csize, dst, seg[i][2]);
            if (got != seg[i][2]) FATAL("lz4 %s seg %d: %zu != %u\n", path, i, got, seg[i][2]);
        } else {
            memcpy(dst, raw + seg[i][0], seg[i][2]);
        }
    }
    free(raw);
    m->base = base;
    m->size = size;
    m->text_off = seg[0][1]; m->text_size = seg[0][2];
    m->ro_off = seg[1][1];   m->ro_size = seg[1][2];
    m->data_off = seg[2][1]; m->data_size = seg[2][2] + bss;

    uint32_t mod0 = *(uint32_t *)(base + 4);
    uint64_t mod = base + mod0;
    m->dynamic = mod + *(int32_t *)(mod + 4);
    uint64_t gnu_hash = 0;
    for (uint64_t *d = (uint64_t *)m->dynamic; d[0] != DT_NULL; d += 2) {
        switch (d[0]) {
        case DT_SYMTAB: m->symtab = base + d[1]; break;
        case DT_STRTAB: m->strtab = base + d[1]; break;
        case DT_HASH: m->nsyms = *(uint32_t *)(base + d[1] + 4); break;
        case DT_GNU_HASH: gnu_hash = base + d[1]; break;
        case 12: m->init = base + d[1]; break;  // DT_INIT
        case 13: m->fini = base + d[1]; break;  // DT_FINI
        case DT_INIT_ARRAY: m->init_array = base + d[1]; break;
        case DT_INIT_ARRAYSZ: m->init_array_sz = d[1]; break;
        case DT_FINI_ARRAY: m->fini_array = base + d[1]; break;
        case DT_FINI_ARRAYSZ: m->fini_array_sz = d[1]; break;
        }
    }
    if (!m->nsyms && gnu_hash) m->nsyms = gnu_hash_nsyms(m, gnu_hash);
    LOG("loaded %-5s at %llx size %llx syms %llu\n", m->name, base, size, m->nsyms);
}

static Elf64Sym *sym(Module *m, uint64_t i) { return (Elf64Sym *)m->symtab + i; }
static const char *symname(Module *m, Elf64Sym *s) { return (const char *)(m->strtab + s->name); }

uint64_t loader_find_symbol(const char *name) {
    for (int mi = 0; mi < g_nmodules; mi++) {
        Module *m = &g_modules[mi];
        for (uint64_t i = 1; i < m->nsyms; i++) {
            Elf64Sym *s = sym(m, i);
            if (s->shndx && !strcmp(symname(m, s), name)) return m->base + s->value;
        }
    }
    return 0;
}

const char *loader_symbol_at(uint64_t addr, uint64_t *offset) {
    const char *best = NULL;
    uint64_t best_addr = 0;
    for (int mi = 0; mi < g_nmodules; mi++) {
        Module *m = &g_modules[mi];
        if (addr < m->base || addr >= m->base + m->size) continue;
        for (uint64_t i = 1; i < m->nsyms; i++) {
            Elf64Sym *s = sym(m, i);
            uint64_t a = m->base + s->value;
            if (s->shndx && (s->info & 0xF) == 2 && a <= addr && a > best_addr) {
                best = symname(m, s);
                best_addr = a;
            }
        }
    }
    if (offset) *offset = addr - best_addr;
    return best;
}

static uint64_t resolve(Module *m, uint32_t symidx) {
    Elf64Sym *s = sym(m, symidx);
    const char *name = symname(m, s);
    if (s->shndx) return m->base + s->value;
    HostFn impl = hle_lookup_impl(name);
    if (impl) return hle_register(name, impl);  // host implementation wins over the guest SDK's
    uint64_t a = loader_find_symbol(name);
    if (a) return a;
    if ((s->info >> 4) == 2) return 0;  // weak undefined
#ifdef CLUSTERTRUCK_GENERIC_TITLE
    // Unity / SDK imports without a host implementation should be visible in the device log.
    // The call still receives the normal unimplemented HLE stub; this is diagnostic, not a fake implementation.
    if (g_missing_import_logs < 64)
        LOG("[loader] no host implementation for import '%s' from %s; using an unimplemented stub\n", name, m->name);
    else if (g_missing_import_logs == 64)
        LOG("[loader] further unimplemented imports suppressed\n");
    g_missing_import_logs++;
#endif
    return hle_register(name, NULL);
}

static void relocate(Module *m) {
    uint64_t rela = 0, relasz = 0, jmprel = 0, pltsz = 0, relr = 0, relrsz = 0;
    for (uint64_t *d = (uint64_t *)m->dynamic; d[0] != DT_NULL; d += 2) {
        if (d[0] == DT_RELA) rela = m->base + d[1];
        if (d[0] == DT_RELASZ) relasz = d[1];
        if (d[0] == DT_JMPREL) jmprel = m->base + d[1];
        if (d[0] == DT_PLTRELSZ) pltsz = d[1];
        if (d[0] == DT_RELR) relr = m->base + d[1];
        if (d[0] == DT_RELRSZ) relrsz = d[1];
    }
    uint64_t packed_relative = relr ? relocate_relr(m, relr, relrsz) : 0;
    int unresolved = 0;
    for (int pass = 0; pass < 2; pass++) {
        Elf64Rela *r = (Elf64Rela *)(pass ? jmprel : rela);
        uint64_t n = (pass ? pltsz : relasz) / sizeof(Elf64Rela);
        for (uint64_t i = 0; i < n; i++) {
            uint64_t *where = (uint64_t *)(m->base + r[i].off);
            uint32_t type = (uint32_t)r[i].info, si = r[i].info >> 32;
            switch (type) {
            case R_RELATIVE: *where = m->base + r[i].addend; break;
            case R_ABS64:
            case R_GLOB_DAT:
            case R_JUMP_SLOT: {
                uint64_t s = resolve(m, si);
                *where = s ? s + r[i].addend : 0;
                if (s >= HLE_BASE && s < HLE_BASE + HLE_SIZE) unresolved++;
                break;
            }
            default: FATAL("%s: reloc type %u\n", m->name, type);
            }
        }
    }
    LOG("relocated %-5s (%llu packed relative, %d imports bound to host stubs)\n", m->name, (unsigned long long)packed_relative, unresolved);
}

void loader_load(const char *exefs) {
    char path[1024];
#ifdef CLUSTERTRUCK_GENERIC_TITLE
    g_missing_import_logs = 0;
#endif
    const struct { const char *name; uint64_t base; } mods[] = {{"main", MAIN_BASE}, {"sdk", SDK_BASE}};  // (not static: Android's bases move)
    for (unsigned i = 0; i < 2; i++) {
        Module *m = &g_modules[g_nmodules++];
        memset(m, 0, sizeof *m);
        m->name = mods[i].name;
        snprintf(path, sizeof path, "%s/%s", exefs, m->name);
        load_nso(m, path, mods[i].base);
    }
#ifdef CLUSTERTRUCK_GENERIC_TITLE
    // Clustertruck's Unity/middleware code lives in subsdk0. Map it before relocations so its exports
    // can satisfy imports in main and sdk. It remains optional for extracted revisions without that module.
    snprintf(path, sizeof path, "%s/subsdk0", exefs);
    struct stat sub_st;
    if (stat(path, &sub_st) == 0 && S_ISREG(sub_st.st_mode)) {
        Module *m = &g_modules[g_nmodules++];
        m->name = "subsdk0";
        load_nso(m, path, SUBSDK0_BASE);
    } else {
        LOG("[loader] optional module subsdk0 is absent\n");
    }
    // Other subsdk modules are not mapped by this first generic multi-module step.
    DIR *dir = opendir(exefs);
    if (dir) {
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            if (strncmp(entry->d_name, "subsdk", 6) || !strcmp(entry->d_name, "subsdk0")) continue;
            snprintf(path, sizeof path, "%s/%s", exefs, entry->d_name);
            struct stat st;
            if (stat(path, &st) == 0 && S_ISREG(st.st_mode))
                LOG("[loader] EXTRA MODULE NOT LOADED: %s (only main, sdk and subsdk0 are currently mapped)\n", entry->d_name);
        }
        closedir(dir);
    }
#endif
    for (int i = 0; i < g_nmodules; i++) relocate(&g_modules[i]);
}
