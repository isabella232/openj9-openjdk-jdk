/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2022, 2022 All Rights Reserved
 * ===========================================================================
 */

/*
 * @test
 * @enablePreview
 * @requires ((os.arch == "amd64" | os.arch == "x86_64") & sun.arch.data.model == "64") | os.arch == "aarch64" | os.arch == "riscv64"
 * | os.arch == "ppc64" | os.arch == "ppc64le" | os.arch == "s390x"
 * @modules java.base/jdk.internal.foreign
 * @run testng/othervm --enable-native-access=ALL-UNNAMED -Dgenerator.sample.factor=17 TestVarArgs
 */

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SegmentScope;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import java.lang.foreign.ValueLayout;
import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.*;

public class TestVarArgs extends CallGeneratorHelper {

    private static boolean isAixOS = System.getProperty("os.name").toLowerCase().contains("aix");
    static final VarHandle VH_IntArray = C_INT.arrayElementVarHandle();
    static final MethodHandle MH_CHECK;

    static final Linker LINKER = Linker.nativeLinker();
    static {
        System.loadLibrary("VarArgs");
        try {
            MH_CHECK = MethodHandles.lookup().findStatic(TestVarArgs.class, "check",
                    MethodType.methodType(void.class, int.class, MemorySegment.class, List.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final MemorySegment VARARGS_ADDR = findNativeOrThrow("varargs");

    @Test(dataProvider = "variadicFunctions")
    public void testVarArgs(int count, String fName, Ret ret, // ignore this stuff
                            List<ParamType> paramTypes, List<StructFieldType> fields) throws Throwable {
        try (Arena arena = Arena.openConfined()) {
            List<Arg> args = makeArgs(arena, paramTypes, fields);
            MethodHandle checker = MethodHandles.insertArguments(MH_CHECK, 2, args);
            MemorySegment writeBack = LINKER.upcallStub(checker, FunctionDescriptor.ofVoid(C_INT, C_POINTER), arena.scope());
            MemorySegment callInfo = arena.allocate(CallInfo.LAYOUT);
            MemoryLayout layout = MemoryLayout.sequenceLayout(args.size(), C_INT);
            MemorySegment argIDs = arena.allocate(layout);

            MemorySegment callInfoPtr = callInfo;

            CallInfo.writeback(callInfo, writeBack);
            CallInfo.argIDs(callInfo, argIDs);

            for (int i = 0; i < args.size(); i++) {
                VH_IntArray.set(argIDs, (long) i, args.get(i).id.ordinal());
            }

            List<MemoryLayout> argLayouts = new ArrayList<>();
            argLayouts.add(C_POINTER); // call info
            argLayouts.add(C_INT); // size

            FunctionDescriptor baseDesc = FunctionDescriptor.ofVoid(argLayouts.toArray(MemoryLayout[]::new));
            Linker.Option varargIndex = Linker.Option.firstVariadicArg(baseDesc.argumentLayouts().size());
            FunctionDescriptor desc = baseDesc.appendArgumentLayouts(args.stream().map(a -> a.layout).toArray(MemoryLayout[]::new));

            MethodHandle downcallHandle = LINKER.downcallHandle(VARARGS_ADDR, desc, varargIndex);

            List<Object> argValues = new ArrayList<>();
            argValues.add(callInfoPtr); // call info
            argValues.add(args.size());  // size
            args.forEach(a -> argValues.add(a.value()));

            downcallHandle.invokeWithArguments(argValues);

            // args checked by upcall
        }
    }

    private static List<ParamType> createParameterTypesForStruct(int extraIntArgs) {
        List<ParamType> paramTypes = new ArrayList<ParamType>();
        for (int i = 0; i < extraIntArgs; i++) {
            paramTypes.add(ParamType.INT);
        }
        paramTypes.add(ParamType.STRUCT);
        return paramTypes;
    }

    private static List<StructFieldType> createFieldsForStruct(int fieldCount, StructFieldType fieldType) {
        List<StructFieldType> fields = new ArrayList<StructFieldType>();
        for (int i = 0; i < fieldCount; i++) {
            fields.add(fieldType);
        }
        return fields;
    }

    @DataProvider(name = "variadicFunctions")
    public static Object[][] variadicFunctions() {
        List<Object[]> downcalls = new ArrayList<>();

        var functionsDowncalls = functions();
        for (var array : functionsDowncalls) {
            downcalls.add(array);
        }

        // Test struct with 4 floats
        int extraIntArgs = 0;
        List<StructFieldType> fields = createFieldsForStruct(4, StructFieldType.FLOAT);
        List<ParamType> paramTypes = createParameterTypesForStruct(extraIntArgs);
        downcalls.add(new Object[] { 0, "", Ret.VOID, paramTypes, fields });

        // Test struct with 4 floats without enough registers for all fields
        extraIntArgs = 6;
        fields = createFieldsForStruct(4, StructFieldType.FLOAT);
        paramTypes = createParameterTypesForStruct(extraIntArgs);
        downcalls.add(new Object[] { 0, "", Ret.VOID, paramTypes, fields });

        // Test struct with 2 doubles without enough registers for all fields
        extraIntArgs = 7;
        fields = createFieldsForStruct(2, StructFieldType.DOUBLE);
        paramTypes = createParameterTypesForStruct(extraIntArgs);
        downcalls.add(new Object[] { 0, "", Ret.VOID, paramTypes, fields });

        // Test struct with 2 ints without enough registers for all fields
        fields = createFieldsForStruct(2, StructFieldType.INT);
        paramTypes = createParameterTypesForStruct(extraIntArgs);
        downcalls.add(new Object[] { 0, "", Ret.VOID, paramTypes, fields });

        return downcalls.toArray(new Object[0][]);
    }

    private static List<Arg> makeArgs(Arena arena, List<ParamType> paramTypes, List<StructFieldType> fields) {
        List<Arg> args = new ArrayList<>();
        for (ParamType pType : paramTypes) {
            MemoryLayout layout = pType.layout(fields);
            TestValue testValue = genTestValue(layout, arena);
            Arg.NativeType type = Arg.NativeType.of(pType.type(fields));
            args.add(pType == ParamType.STRUCT
                ? Arg.structArg(type, layout, testValue)
                : Arg.primitiveArg(type, layout, testValue));
        }
        return args;
    }

    private static void check(int index, MemorySegment ptr, List<Arg> args) {
        Arg varArg = args.get(index);
        MemoryLayout layout = varArg.layout;
        MethodHandle getter = varArg.getter;
        try (Arena arena = Arena.openConfined()) {
            MemorySegment seg = MemorySegment.ofAddress(ptr.address(), layout.byteSize(), arena.scope());
            /* Vararg float is promoted to double (8 bytes) in native (See libVarArgs.c)
             * in which case float must be converted back from double at the same memory
             * address in java on the Big-Endian(BE) platforms such as AIX.
             */
            if (isAixOS && (layout instanceof ValueLayout) && (((ValueLayout)layout).carrier() == float.class)) {
                MemorySegment doubleSegmt = MemorySegment.ofAddress(ptr, JAVA_DOUBLE.byteSize(), session);
                seg.set(JAVA_FLOAT, 0, (float)doubleSegmt.get(JAVA_DOUBLE, 0));
            }
            Object obj = getter.invoke(seg);
            varArg.check(obj);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static class CallInfo {
        static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
                C_POINTER.withName("writeback"), // writeback
                C_POINTER.withName("argIDs")); // arg ids

        static final VarHandle VH_writeback = LAYOUT.varHandle(groupElement("writeback"));
        static final VarHandle VH_argIDs = LAYOUT.varHandle(groupElement("argIDs"));

        static void writeback(MemorySegment seg, MemorySegment addr) {
            VH_writeback.set(seg, addr);
        }
        static void argIDs(MemorySegment seg, MemorySegment addr) {
            VH_argIDs.set(seg, addr);
        }
    }

    private static final class Arg {
        private final TestValue value;

        final NativeType id;
        final MemoryLayout layout;
        final MethodHandle getter;

        private Arg(NativeType id, MemoryLayout layout, TestValue value, MethodHandle getter) {
            this.id = id;
            this.layout = layout;
            this.value = value;
            this.getter = getter;
        }

        private static Arg primitiveArg(NativeType id, MemoryLayout layout, TestValue value) {
            return new Arg(id, layout, value, layout.varHandle().toMethodHandle(VarHandle.AccessMode.GET));
        }

        private static Arg structArg(NativeType id, MemoryLayout layout, TestValue value) {
            return new Arg(id, layout, value, MethodHandles.identity(MemorySegment.class));
        }

        public void check(Object actual) {
            value.check().accept(actual);
        }

        public Object value() {
            return value.value();
        }

        enum NativeType {
            INT,
            FLOAT,
            DOUBLE,
            POINTER,
            S_I,
            S_F,
            S_D,
            S_P,
            S_II,
            S_IF,
            S_ID,
            S_IP,
            S_FI,
            S_FF,
            S_FD,
            S_FP,
            S_DI,
            S_DF,
            S_DD,
            S_DP,
            S_PI,
            S_PF,
            S_PD,
            S_PP,
            S_III,
            S_IIF,
            S_IID,
            S_IIP,
            S_IFI,
            S_IFF,
            S_IFD,
            S_IFP,
            S_IDI,
            S_IDF,
            S_IDD,
            S_IDP,
            S_IPI,
            S_IPF,
            S_IPD,
            S_IPP,
            S_FII,
            S_FIF,
            S_FID,
            S_FIP,
            S_FFI,
            S_FFF,
            S_FFD,
            S_FFP,
            S_FDI,
            S_FDF,
            S_FDD,
            S_FDP,
            S_FPI,
            S_FPF,
            S_FPD,
            S_FPP,
            S_DII,
            S_DIF,
            S_DID,
            S_DIP,
            S_DFI,
            S_DFF,
            S_DFD,
            S_DFP,
            S_DDI,
            S_DDF,
            S_DDD,
            S_DDP,
            S_DPI,
            S_DPF,
            S_DPD,
            S_DPP,
            S_PII,
            S_PIF,
            S_PID,
            S_PIP,
            S_PFI,
            S_PFF,
            S_PFD,
            S_PFP,
            S_PDI,
            S_PDF,
            S_PDD,
            S_PDP,
            S_PPI,
            S_PPF,
            S_PPD,
            S_PPP,
            S_FFFF,
            ;

            public static NativeType of(String type) {
                return NativeType.valueOf(switch (type) {
                    case "int" -> "INT";
                    case "float" -> "FLOAT";
                    case "double" -> "DOUBLE";
                    case "void*" -> "POINTER";
                    default -> type.substring("struct ".length());
                });
            }
        }
    }

}
