# Third-party notices

## SavageWindRecomp loader format support

This project adapts the generic ELF dynamic-symbol-counting logic for `DT_GNU_HASH` and packed relative relocation decoding for `DT_RELR` from the loader in [SavageWindRecomp](https://github.com/Revivedx/SavageWindRecomp), by Revivedx and the Savage Wind Recomp contributors. The adaptations in this repository add module-image bounds checks and Android probe tests.

Copyright (c) 2026 Revivedx and the Savage Wind Recomp contributors  
Copyright (c) 2026 Peiikai (LAS Recomp, on which Savage Wind Recomp is built)

The relevant upstream code is licensed under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

This notice applies only to the portions adapted from SavageWindRecomp. Other source files and third-party dependencies retain their own applicable notices and licenses.
