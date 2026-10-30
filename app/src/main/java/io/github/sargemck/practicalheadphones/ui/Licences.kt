package io.github.sargemck.practicalheadphones.ui

/** Shown on the About screen. `LicencesTest` checks these match LICENSE and NOTICE at the repo root. */
object Licences {
    const val BOSECTL_URL = "https://github.com/aaronsb/bosectl"

    private const val MIT_TERMS = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

    val LICENSE = """MIT License

Copyright (c) 2026 Sargemck

$MIT_TERMS
"""

    val NOTICE = """Practical Headphones
Copyright (c) 2026 Sargemck. MIT License, see LICENSE.

Not affiliated with Bose. Bose and QuietComfort are trademarks of Bose Corporation.

Protocol knowledge and ported logic come from bosectl:
$BOSECTL_URL

MIT License

Copyright (c) 2026 Aaron Bockelie

$MIT_TERMS

Icons are Material Icons by Google, Apache License 2.0:
https://www.apache.org/licenses/LICENSE-2.0
"""
}
