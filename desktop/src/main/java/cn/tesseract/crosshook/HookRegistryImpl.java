package cn.tesseract.crosshook;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.objectweb.asm.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HookRegistryImpl implements HookRegistry {
    private static final String CALLBACK_DESC = Type.getInternalName(Callback.class);
    private static final String HOOK_DESC = Type.getDescriptor(Hook.class);
    private static final Map<String, List<HookDef>> HOOKS = new ConcurrentHashMap<>();
    private static volatile Instrumentation instrumentation;

    public static synchronized void init() {
        if (instrumentation != null) return;
        try {
            instrumentation = ByteBuddyAgent.install();
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Retransform is not supported by this JVM");
            }
            instrumentation.addTransformer(new HookTransformer(), true);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to self-attach agent", exception);
        }
    }

    public synchronized void register(String hookClass) {
        if (instrumentation == null) init();
        try {
            List<HookDef> parsedHookDefinitions = parseHooks(hookClass);
            Set<String> affectedTargetClasses = new LinkedHashSet<>();
            for (HookDef hookDefinition : parsedHookDefinitions) {
                HOOKS.computeIfAbsent(hookDefinition.targetClass, key -> new CopyOnWriteArrayList<>()).add(hookDefinition);
                affectedTargetClasses.add(hookDefinition.targetClass);
            }
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (affectedTargetClasses.contains(loadedClass.getName())) {
                    instrumentation.retransformClasses(loadedClass);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to register: " + hookClass, exception);
        }
    }

    private static List<HookDef> parseHooks(String hookClass) throws Exception {
        byte[] classBytes = loadClassBytes(hookClass);
        List<HookDef> hookDefinitions = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String methodName, String methodDescriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!HOOK_DESC.equals(annotationDescriptor))
                            return super.visitAnnotation(annotationDescriptor, visible);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            String targetClassName = null;
                            String targetMethodName = null;
                            String injector = Hook.HEAD;
                            int priority = 0;

                            @Override
                            public void visit(String attributeName, Object value) {
                                if ("targetClass".equals(attributeName)) targetClassName = value.toString();
                                else if ("targetMethod".equals(attributeName)) targetMethodName = value.toString();
                                else if ("injector".equals(attributeName)) injector = value.toString();
                                else if ("priority".equals(attributeName)) priority = (int) value;
                            }

                            @Override
                            public void visitEnd() {
                                try {
                                    hookDefinitions.add(createHookDef(
                                            hookClass,
                                            methodName,
                                            methodDescriptor,
                                            targetClassName,
                                            targetMethodName,
                                            injector,
                                            priority,
                                            methodAccess));
                                } catch (Exception exception) {
                                    throw new RuntimeException(exception);
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hookDefinitions;
    }

    private static HookDef createHookDef(
            String hookClassName,
            String hookMethodName,
            String hookMethodDescriptor,
            String targetClassName,
            String targetMethodName,
            String injector,
            int priority,
            int methodAccess
    ) throws Exception {
        if ((methodAccess & Opcodes.ACC_PUBLIC) == 0 || (methodAccess & Opcodes.ACC_STATIC) == 0)
            throw new IllegalArgumentException("Hook must be public static");
        Type[] hookParameterTypes = Type.getArgumentTypes(hookMethodDescriptor);
        if (hookParameterTypes.length == 0 || !Type.getInternalName(Callback.class).equals(hookParameterTypes[0].getInternalName()))
            throw new IllegalArgumentException("First parameter must be Callback");
        if (targetClassName == null || targetClassName.isEmpty())
            targetClassName = inferTargetClass(hookClassName, hookMethodDescriptor);
        Type[] targetParameterTypes = new Type[hookParameterTypes.length - 1];
        System.arraycopy(hookParameterTypes, 1, targetParameterTypes, 0, targetParameterTypes.length);
        String resolvedTargetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? hookMethodName : targetMethodName;
        String[] targetMethodInfo = findMethod(targetClassName, resolvedTargetMethodName, targetParameterTypes);
        if (targetMethodInfo == null)
            throw new IllegalArgumentException("Cannot find: " + targetClassName + "." + resolvedTargetMethodName);
        if ("<init>".equals(targetMethodInfo[0]) && Hook.HEAD.equalsIgnoreCase(injector))
            throw new IllegalArgumentException("Constructor does not support head injector, use tail");
        return new HookDef(
                hookClassName,
                hookMethodName,
                hookMethodDescriptor,
                hookParameterTypes.length,
                targetClassName,
                targetMethodInfo[0],
                targetMethodInfo[1],
                injector,
                priority);
    }

    private static String inferTargetClass(String hookClassName, String hookMethodDescriptor) throws Exception {
        byte[] classBytes = loadClassBytes(hookClassName);
        final String[] inferredTargetClass = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String methodName, String methodDescriptor, String signature, String[] exceptions) {
                if (methodDescriptor.equals(hookMethodDescriptor) && signature != null && signature.startsWith("(L")) {
                    int callbackTypeStartIndex = signature.indexOf("Lcn/tesseract/crosshook/Callback<");
                    if (callbackTypeStartIndex >= 0) {
                        int genericTypeStart = callbackTypeStartIndex + "Lcn/tesseract/crosshook/Callback<".length();
                        int genericTypeEnd = signature.indexOf(">", genericTypeStart);
                        if (genericTypeEnd > genericTypeStart) {
                            String genericTypeDescriptor = signature.substring(genericTypeStart, genericTypeEnd);
                            if (genericTypeDescriptor.startsWith("L") && genericTypeDescriptor.endsWith(";"))
                                inferredTargetClass[0] = genericTypeDescriptor.substring(1, genericTypeDescriptor.length() - 1).replace('/', '.');
                        }
                    }
                }
                return super.visitMethod(methodAccess, methodName, methodDescriptor, signature, exceptions);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        if (inferredTargetClass[0] != null) return inferredTargetClass[0];
        throw new IllegalArgumentException("Specify targetClass");
    }

    private static String[] findMethod(String className, String methodName, Type[] parameterTypes) throws Exception {
        byte[] classBytes = loadClassBytes(className);
        final String[][] matchedMethodInfo = {{null, null}};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String candidateMethodName, String candidateMethodDescriptor, String signature, String[] exceptions) {
                if (methodName.equals(candidateMethodName)) {
                    Type[] candidateParameterTypes = Type.getArgumentTypes(candidateMethodDescriptor);
                    if (candidateParameterTypes.length == parameterTypes.length) {
                        boolean isMatch = true;
                        for (int index = 0; index < candidateParameterTypes.length; index++) {
                            if (!candidateParameterTypes[index].equals(parameterTypes[index])) {
                                isMatch = false;
                                break;
                            }
                        }
                        if (isMatch)
                            matchedMethodInfo[0] = new String[]{candidateMethodName, candidateMethodDescriptor};
                    }
                }
                return super.visitMethod(methodAccess, candidateMethodName, candidateMethodDescriptor, signature, exceptions);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return matchedMethodInfo[0][0] != null ? matchedMethodInfo[0] : null;
    }

    private static byte[] transformClass(String className, byte[] classBytes, List<HookDef> hookDefinitions) {
        Map<String, List<HookDef>> hooksByMethod = new HashMap<>();
        hookDefinitions.forEach(definition -> hooksByMethod.computeIfAbsent(definition.key(), key -> new ArrayList<>()).add(definition));
        hooksByMethod.values().forEach(definitions -> definitions.sort((left, right) -> Integer.compare(right.priority, left.priority)));
        ClassWriter classWriter = new ClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String methodName, String methodDescriptor, String signature, String[] exceptions) {
                MethodVisitor methodVisitor = super.visitMethod(methodAccess, methodName, methodDescriptor, signature, exceptions);
                if (methodVisitor == null) return null;
                List<HookDef> methodHooks = hooksByMethod.get(className.replace('.', '/') + '#' + methodName + methodDescriptor);
                if (methodHooks == null || (methodAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                    return methodVisitor;
                return new Inserter(methodVisitor, methodAccess, className, methodName, methodDescriptor, methodHooks);
            }
        }, ClassReader.EXPAND_FRAMES);
        return classWriter.toByteArray();
    }

    private static byte[] loadClassBytes(String className) throws IOException {
        String resourcePath = className.replace('.', '/') + ".class";
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) inputStream = HookRegistry.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) inputStream = ClassLoader.getSystemResourceAsStream(resourcePath);
        if (inputStream == null) throw new IOException("Cannot load: " + className);
        try (InputStream classStream = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = classStream.read(buffer)) != -1) outputStream.write(buffer, 0, bytesRead);
            return outputStream.toByteArray();
        }
    }

    private static void dumpClass(String className, byte[] classBytes) {
        String dumpDir = System.getProperty("crosshook.dumpDir");
        boolean dumpEnabled = Boolean.parseBoolean(System.getProperty("crosshook.dump", "false")) || (dumpDir != null && !dumpDir.trim().isEmpty());
        if (!dumpEnabled) return;
        try {
            Path outputPath = Paths.get(dumpDir != null && !dumpDir.isEmpty() ? dumpDir : "build/crosshook-dump").resolve(className.replace('.', '/') + ".class");
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, classBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Dump failed", exception);
        }
    }

    private static final class HookTransformer implements ClassFileTransformer {
        public byte[] transform(ClassLoader loader, String internalClassName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            if (internalClassName == null) {
                return null;
            }
            String className = internalClassName.replace('/', '.');
            List<HookDef> hookDefinitions = HOOKS.get(className);
            if (hookDefinitions == null || hookDefinitions.isEmpty()) {
                return null;
            }
            try {
                byte[] transformedClassBytes = transformClass(className, classfileBuffer, new ArrayList<>(hookDefinitions));
                dumpClass(className, transformedClassBytes);
                return transformedClassBytes;
            } catch (Throwable throwable) {
                IllegalClassFormatException formatException = new IllegalClassFormatException("Failed to transform class: " + className);
                formatException.initCause(throwable);
                throw formatException;
            }
        }
    }

    private static final class Inserter extends MethodVisitor {
        private final String ownerInternal;
        private final Type[] argumentTypes;
        private final Type returnType;
        private final int[] argumentSlots;
        private final int callbackSlot;
        private final int argsArraySlot;
        private final int tempReturnSlot;
        private final boolean staticMethod;
        private final boolean constructorMethod;
        private final List<HookDef> hookDefinitions;

        Inserter(MethodVisitor methodVisitor, int methodAccess, String ownerClassName, String methodName, String methodDescriptor, List<HookDef> methodHookDefinitions) {
            super(Opcodes.ASM9, methodVisitor);
            this.ownerInternal = ownerClassName.replace('.', '/');
            this.hookDefinitions = methodHookDefinitions;
            this.argumentTypes = Type.getArgumentTypes(methodDescriptor);
            this.returnType = Type.getReturnType(methodDescriptor);
            this.staticMethod = (methodAccess & Opcodes.ACC_STATIC) != 0;
            this.constructorMethod = "<init>".equals(methodName);
            this.argumentSlots = new int[argumentTypes.length];
            int nextSlot = staticMethod ? 0 : 1;
            for (int index = 0; index < argumentTypes.length; index++) {
                argumentSlots[index] = nextSlot;
                nextSlot += argumentTypes[index].getSize();
            }
            this.argsArraySlot = nextSlot + 1;
            this.callbackSlot = nextSlot + 2;
            this.tempReturnSlot = nextSlot + 3;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (constructorMethod) return;
            newCallback();
            callHeadHooks();
            checkProceed();
        }

        @Override
        public void visitInsn(int op) {
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                handleReturn(op);
                return;
            }
            super.visitInsn(op);
        }

        private void newCallback() {
            mv.visitTypeInsn(Opcodes.NEW, CALLBACK_DESC);
            mv.visitInsn(Opcodes.DUP);
            if (staticMethod) mv.visitInsn(Opcodes.ACONST_NULL);
            else mv.visitVarInsn(Opcodes.ALOAD, 0);
            buildArgs();
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CALLBACK_DESC, "<init>", "(Ljava/lang/Object;[Ljava/lang/Object;)V", false);
            mv.visitVarInsn(Opcodes.ASTORE, callbackSlot);
            mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "getArgs", "()[Ljava/lang/Object;", false);
            mv.visitVarInsn(Opcodes.ASTORE, argsArraySlot);
        }

        private void buildArgs() {
            push(argumentTypes.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int index = 0; index < argumentTypes.length; index++) {
                mv.visitInsn(Opcodes.DUP);
                push(index);
                mv.visitVarInsn(argumentTypes[index].getOpcode(Opcodes.ILOAD), argumentSlots[index]);
                box(argumentTypes[index]);
                mv.visitInsn(Opcodes.AASTORE);
            }
        }

        private void callHeadHooks() {
            boolean hasHeadHook = false;
            for (HookDef hookDefinition : hookDefinitions) {
                if (!Hook.HEAD.equalsIgnoreCase(hookDefinition.injector)) continue;
                hasHeadHook = true;
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
                loadHookArgsFromCallback(hookDefinition);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookDefinition.owner(), hookDefinition.name, hookDefinition.desc, false);
            }
            if (hasHeadHook) syncLocals();
        }

        private void loadHookArgsFromCallback(HookDef hookDefinition) {
            for (int index = 1; index < hookDefinition.paramCount; index++) {
                mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
                push(index - 1);
                mv.visitInsn(Opcodes.AALOAD);
                unboxToStack(argumentTypes[index - 1]);
            }
        }

        private void syncLocals() {
            if (!staticMethod) {
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "getThisObject", "()Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, ownerInternal);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
            }
            for (int index = 0; index < argumentTypes.length; index++) {
                mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
                push(index);
                mv.visitInsn(Opcodes.AALOAD);
                unboxToLocal(argumentTypes[index], argumentSlots[index]);
            }
        }

        private void checkProceed() {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "shouldProceed", "()Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, next);
            skipReturn();
            mv.visitLabel(next);
        }

        private void skipReturn() {
            if (returnType.getSort() == Type.VOID) {
                mv.visitInsn(Opcodes.RETURN);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "getReturnValue", "()Ljava/lang/Object;", false);
                unboxToStack(returnType);
                mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
            }
        }

        private void handleReturn(int op) {
            if (op == Opcodes.RETURN) {
                if (constructorMethod) newCallback();
                callTailHooks();
                mv.visitInsn(Opcodes.RETURN);
                return;
            }
            if (returnType.getSize() == 2) mv.visitInsn(Opcodes.DUP2);
            else mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(returnType.getOpcode(Opcodes.ISTORE), tempReturnSlot);
            mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
            mv.visitVarInsn(returnType.getOpcode(Opcodes.ILOAD), tempReturnSlot);
            box(returnType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "setReturnValue", "(Ljava/lang/Object;)V", false);
            callTailHooks();
            mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLBACK_DESC, "getReturnValue", "()Ljava/lang/Object;", false);
            unboxToStack(returnType);
            mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        }

        private void callTailHooks() {
            for (HookDef hookDefinition : hookDefinitions) {
                if (!Hook.TAIL.equalsIgnoreCase(hookDefinition.injector)) continue;
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot);
                loadHookArgsFromCallback(hookDefinition);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookDefinition.owner(), hookDefinition.name, hookDefinition.desc, false);
            }
        }

        private void box(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.BOOLEAN)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            else if (sort == Type.CHAR)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            else if (sort == Type.BYTE)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            else if (sort == Type.SHORT)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            else if (sort == Type.INT)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            else if (sort == Type.FLOAT)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            else if (sort == Type.LONG)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            else if (sort == Type.DOUBLE)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        }

        private void unboxToLocal(Type valueType, int localSlot) {
            int sort = valueType.getSort();
            if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, valueType.getInternalName());
                mv.visitVarInsn(Opcodes.ASTORE, localSlot);
            } else {
                String wrapperType, accessorMethod, accessorDescriptor;
                if (sort == Type.BOOLEAN) {
                    wrapperType = "java/lang/Boolean";
                    accessorMethod = "booleanValue";
                    accessorDescriptor = "()Z";
                } else if (sort == Type.CHAR) {
                    wrapperType = "java/lang/Character";
                    accessorMethod = "charValue";
                    accessorDescriptor = "()C";
                } else if (sort == Type.BYTE) {
                    wrapperType = "java/lang/Byte";
                    accessorMethod = "byteValue";
                    accessorDescriptor = "()B";
                } else if (sort == Type.SHORT) {
                    wrapperType = "java/lang/Short";
                    accessorMethod = "shortValue";
                    accessorDescriptor = "()S";
                } else if (sort == Type.INT) {
                    wrapperType = "java/lang/Integer";
                    accessorMethod = "intValue";
                    accessorDescriptor = "()I";
                } else if (sort == Type.FLOAT) {
                    wrapperType = "java/lang/Float";
                    accessorMethod = "floatValue";
                    accessorDescriptor = "()F";
                } else if (sort == Type.LONG) {
                    wrapperType = "java/lang/Long";
                    accessorMethod = "longValue";
                    accessorDescriptor = "()J";
                } else {
                    wrapperType = "java/lang/Double";
                    accessorMethod = "doubleValue";
                    accessorDescriptor = "()D";
                }
                mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperType);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperType, accessorMethod, accessorDescriptor, false);
                mv.visitVarInsn(valueType.getOpcode(Opcodes.ISTORE), localSlot);
            }
        }

        private void unboxToStack(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, valueType.getInternalName());
                return;
            }
            String wrapperType, accessorMethod, accessorDescriptor;
            if (sort == Type.BOOLEAN) {
                wrapperType = "java/lang/Boolean";
                accessorMethod = "booleanValue";
                accessorDescriptor = "()Z";
            } else if (sort == Type.CHAR) {
                wrapperType = "java/lang/Character";
                accessorMethod = "charValue";
                accessorDescriptor = "()C";
            } else if (sort == Type.BYTE) {
                wrapperType = "java/lang/Byte";
                accessorMethod = "byteValue";
                accessorDescriptor = "()B";
            } else if (sort == Type.SHORT) {
                wrapperType = "java/lang/Short";
                accessorMethod = "shortValue";
                accessorDescriptor = "()S";
            } else if (sort == Type.INT) {
                wrapperType = "java/lang/Integer";
                accessorMethod = "intValue";
                accessorDescriptor = "()I";
            } else if (sort == Type.FLOAT) {
                wrapperType = "java/lang/Float";
                accessorMethod = "floatValue";
                accessorDescriptor = "()F";
            } else if (sort == Type.LONG) {
                wrapperType = "java/lang/Long";
                accessorMethod = "longValue";
                accessorDescriptor = "()J";
            } else {
                wrapperType = "java/lang/Double";
                accessorMethod = "doubleValue";
                accessorDescriptor = "()D";
            }
            mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperType, accessorMethod, accessorDescriptor, false);
        }

        private void push(int value) {
            if (value >= -1 && value <= 5) mv.visitInsn(Opcodes.ICONST_0 + value);
            else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, value);
            else mv.visitIntInsn(Opcodes.SIPUSH, value);
        }
    }

    private static class HookDef {
        final String hookClass, name, desc, targetClass, targetMethod, targetDesc, injector;
        final int paramCount, priority;

        HookDef(String hookClass,
                String hookMethodName,
                String hookMethodDescriptor,
                int hookParamCount,
                String targetClass,
                String targetMethod,
                String targetDescriptor,
                String injector,
                int priority
        ) {
            this.hookClass = hookClass;
            this.name = hookMethodName;
            this.desc = hookMethodDescriptor;
            this.paramCount = hookParamCount;
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
            this.targetDesc = targetDescriptor;
            this.injector = injector;
            this.priority = priority;
        }

        String owner() {
            return hookClass.replace('.', '/');
        }

        String key() {
            return targetClass.replace('.', '/') + '#' + targetMethod + targetDesc;
        }
    }
}