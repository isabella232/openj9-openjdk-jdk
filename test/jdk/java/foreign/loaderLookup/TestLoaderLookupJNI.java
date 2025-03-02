/*
 *  Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *  Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2022, 2022 All Rights Reserved
 * ===========================================================================
 */

import org.testng.annotations.Test;

import java.lang.foreign.SymbolLookup;

import static org.testng.Assert.*;

/*
 * @test
 * @enablePreview
 * @requires ((os.arch == "amd64" | os.arch == "x86_64") & sun.arch.data.model == "64") | os.arch == "aarch64" | os.arch == "riscv64"
 * | os.arch == "ppc64" | os.arch == "ppc64le" | os.arch == "s390x"
 * @run testng/othervm TestLoaderLookupJNI
 */
public class TestLoaderLookupJNI {

    static {
        System.loadLibrary("LoaderLookupInvoker");
    }

    @Test
    void testLoaderLookupJNI() {
        SymbolLookup loaderLookup = SymbolLookup.loaderLookup();
        assertTrue(loaderLookup.find("Java_TestLoaderLookupJNI_loaderLookup0").isPresent());
        // now try calling via JNI
        loaderLookup = loaderLookup0(); // lookup backed by application loader, so can see same symbols
        assertTrue(loaderLookup.find("Java_TestLoaderLookupJNI_loaderLookup0").isPresent());
    }

    static native SymbolLookup loaderLookup0();
}
