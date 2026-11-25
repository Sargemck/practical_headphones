package io.github.sargemck.practicalheadphones.ui

/** Shown on the About screen. `LicencesTest` checks these match LICENSE and NOTICE at the repo root. */
object Licences {
    const val BOSECTL_URL = "https://github.com/aaronsb/bosectl"
    const val SONY_CLIENT_URL = "https://github.com/Plutoberth/SonyHeadphonesClient"
    const val SOUNDCORE_BRIDGE_URL = "https://github.com/mervin008/soundcorebridge"
    const val OMARCHY_URL = "https://github.com/ncr/omarchy-headphones"

    private const val MIT_TERMS = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

    val LICENSE = """MIT License

Copyright (c) 2026 Sargemck

$MIT_TERMS
"""

    val NOTICE = """Practical Headphones
Copyright (c) 2026 Sargemck. MIT License, see LICENSE.

Not affiliated with Bose, Sony, Anker/Soundcore, Samsung, Nothing, or HARMAN/JBL. Bose and QuietComfort are trademarks of Bose Corporation. Sony is a trademark of Sony Group Corporation. Soundcore is a trademark of Anker Innovations Limited. Samsung and Galaxy Buds are trademarks of Samsung Electronics Co., Ltd. Nothing and CMF are trademarks of Nothing Technology Limited. JBL is a trademark of HARMAN International Industries, Incorporated.

Protocol knowledge and ported logic come from:

1. bosectl ($BOSECTL_URL)
MIT License — Copyright (c) 2026 Aaron Bockelie

2. SonyHeadphonesClient ($SONY_CLIENT_URL)
MIT License — Copyright (c) 2020 Nir Harel, Mor Gal, Sem Visscher, jimzrt, guilhermealbm, and other contributors

3. soundcorebridge ($SOUNDCORE_BRIDGE_URL)
MIT License — Copyright (c) 2026 Mervin

4. omarchy-headphones ($OMARCHY_URL)
MIT License — Copyright (c) 2026 Jacek Becela

$MIT_TERMS

Icons are Material Icons by Google, Apache License 2.0:
https://www.apache.org/licenses/LICENSE-2.0
"""
}
