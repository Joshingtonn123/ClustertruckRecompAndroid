import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from lifter import AsmInsn, Insn, Unsupported, emit_asm_batch


class GenericLifterSafetyTests(unittest.TestCase):
    def test_nzcv_batch_reserves_x16_for_flags(self):
        batch = [
            Insn("asm", asm=AsmInsn(0, set(), [(0, 0), (5, 1), (10, 2), (15, 3)], True)),
            Insn("asm", asm=AsmInsn(0, set(), [(0, 4), (5, 5), (10, 6), (15, 7)], False)),
        ]
        code = emit_asm_batch(batch)
        self.assertIn("ldr x17, [%0, #56]", code)
        self.assertIn("str x17, [%0, #56]", code)
        self.assertNotIn("ldr x16, [%0, #56]", code)
        self.assertNotIn("str x16, [%0, #56]", code)

    def test_scratch_exhaustion_is_checked_for_constant_operands(self):
        batch = [
            Insn("asm", asm=AsmInsn(0, set(), [(0, 0), (5, 1), (10, 2), (15, 3)], True)),
            Insn("asm", asm=AsmInsn(0, set(), [(0, 4), (5, 5), (10, 6), (15, 7)], False, [(20, 0x1234)])),
        ]
        with self.assertRaises(Unsupported):
            emit_asm_batch(batch)


if __name__ == "__main__":
    unittest.main()
