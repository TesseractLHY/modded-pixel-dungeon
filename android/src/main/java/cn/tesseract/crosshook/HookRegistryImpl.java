package cn.tesseract.crosshook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

public final class HookRegistryImpl implements HookRegistry {
    private static final Method HOOK_METHOD;
    private static final Method CALLBACK_METHOD;
    private static final Field RETURN_VALUE_FIELD;
    private static final Set<String> REGISTERED_HOOK_CLASSES = new HashSet<>();

    static {
        try {
            HOOK_METHOD = XposedBridge.class.getDeclaredMethod("hook0", Object.class, Member.class, Method.class);
            HOOK_METHOD.setAccessible(true);
            CALLBACK_METHOD = HookContext.class.getDeclaredMethod("callback", Object[].class);
            CALLBACK_METHOD.setAccessible(true);
            RETURN_VALUE_FIELD = Callback.class.getDeclaredField("returnValue");
            RETURN_VALUE_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Member> findTargets(HookDef hookDef) {
        List<Member> targets = new ArrayList<>();
        if ("<init>".equals(hookDef.targetMethod)) {
            for (Constructor<?> constructor : hookDef.targetClass.getDeclaredConstructors())
                if (Arrays.equals(constructor.getParameterTypes(), hookDef.parameterTypes))
                    targets.add(constructor);
        } else {
            for (Class<?> currentClass = hookDef.targetClass; currentClass != null; currentClass = currentClass.getSuperclass())
                for (Method targetMethod : currentClass.getDeclaredMethods())
                    if (targetMethod.getName().equals(hookDef.targetMethod) && Arrays.equals(targetMethod.getParameterTypes(), hookDef.parameterTypes))
                        targets.add(targetMethod);
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("No target found: " + hookDef);
        return targets;
    }

    private static Class<?> targetClass(Method hookMethod, Hook annotation) throws ClassNotFoundException {
        String name = annotation.targetClass().trim();
        if (!name.isEmpty()) return Class.forName(name);
        Type callbackType = hookMethod.getGenericParameterTypes()[0];
        if (callbackType instanceof ParameterizedType) {
            Type genericType = ((ParameterizedType) callbackType).getActualTypeArguments()[0];
            if (genericType instanceof Class<?>) return (Class<?>) genericType;
            if (genericType instanceof ParameterizedType && ((ParameterizedType) genericType).getRawType() instanceof Class<?>)
                return (Class<?>) ((ParameterizedType) genericType).getRawType();
        }
        throw new IllegalArgumentException("targetClass is required: " + hookMethod);
    }

    public synchronized void register(String hookClassName) {
        if (hookClassName == null || hookClassName.trim().isEmpty())
            throw new IllegalArgumentException("hookClassName must not be empty");
        if (!REGISTERED_HOOK_CLASSES.add(hookClassName)) return;

        try {
            Map<Member, HookContext> contextsByTarget = new HashMap<>();
            for (Method hookMethod : Class.forName(hookClassName).getDeclaredMethods()) {
                Hook annotation = hookMethod.getAnnotation(Hook.class);
                if (annotation == null) continue;
                HookDef hookDef = new HookDef(hookMethod, annotation);
                for (Member target : findTargets(hookDef)) {
                    HookContext context = contextsByTarget.get(target);
                    if (context == null)
                        contextsByTarget.put(target, context = new HookContext(target));
                    context.hooks.add(hookDef);
                }
            }
            for (HookContext context : contextsByTarget.values()) {
                Collections.sort(context.hooks, (leftHook, rightHook) -> rightHook.priority - leftHook.priority);
                context.backup = (Member) HOOK_METHOD.invoke(null, context, context.method, CALLBACK_METHOD);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register: " + hookClassName, e);
        }
    }

    private static final class HookDef {
        final Method hookMethod;
        final Class<?> targetClass;
        final String targetMethod;
        final Class<?>[] parameterTypes;
        final String injector;
        final int priority;

        HookDef(Method hookMethod, Hook annotation) throws ClassNotFoundException {
            if (!Modifier.isPublic(hookMethod.getModifiers()) || !Modifier.isStatic(hookMethod.getModifiers()))
                throw new IllegalArgumentException("Hook must be public static: " + hookMethod);
            Class<?>[] parameterTypes = hookMethod.getParameterTypes();
            if (parameterTypes.length == 0 || parameterTypes[0] != Callback.class)
                throw new IllegalArgumentException("First parameter must be Callback: " + hookMethod);
            this.hookMethod = hookMethod;
            this.targetClass = targetClass(hookMethod, annotation);
            this.targetMethod = annotation.targetMethod().trim().isEmpty() ? hookMethod.getName() : annotation.targetMethod().trim();
            this.parameterTypes = Arrays.copyOfRange(parameterTypes, 1, parameterTypes.length);
            this.injector = annotation.injector().trim().isEmpty() ? Hook.HEAD : annotation.injector().trim().toLowerCase();
            if (!Hook.HEAD.equals(injector) && !Hook.TAIL.equals(injector))
                throw new IllegalArgumentException("injector must be head or tail: " + hookMethod);
            this.priority = annotation.priority();
            hookMethod.setAccessible(true);
        }

        @Override
        public String toString() {
            return targetClass.getName() + "#" + targetMethod + Arrays.toString(parameterTypes);
        }
    }

    public static final class HookContext {
        final Member method;
        final List<HookDef> hooks = new ArrayList<>();
        Member backup;

        HookContext(Member method) {
            this.method = method;
        }

        private static void invoke(HookDef hookDef, Callback<Object> callback) throws IllegalAccessException, InvocationTargetException {
            Object[] arguments = new Object[callback.getArgs().length + 1];
            arguments[0] = callback;
            System.arraycopy(callback.getArgs(), 0, arguments, 1, callback.getArgs().length);
            hookDef.hookMethod.invoke(null, arguments);
        }

        public Object callback(Object[] args) throws Throwable {
            int argCount = args == null ? 0 : args.length;
            int parameterCount = method instanceof Method
                    ? ((Method) method).getParameterTypes().length
                    : ((Constructor<?>) method).getParameterTypes().length;
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            Object thisObject = null;
            Object[] callArgs;
            if (isStatic) {
                callArgs = argCount == 0 ? new Object[0] : args;
            } else if (argCount == parameterCount + 1) {
                thisObject = args[0];
                callArgs = Arrays.copyOfRange(args, 1, argCount);
            } else if (argCount == parameterCount) {
                callArgs = argCount == 0 ? new Object[0] : args;
            } else {
                throw new IllegalArgumentException("Unexpected callback arguments for " + method + ": expected " + parameterCount + " or " + (parameterCount + 1) + ", got " + argCount);
            }

            Callback<Object> callback = new Callback<>(thisObject, callArgs);
            for (HookDef hookDef : hooks)
                if (Hook.HEAD.equals(hookDef.injector)) {
                    invoke(hookDef, callback);
                    if (!callback.shouldProceed()) return callback.getReturnValue();
                }
            RETURN_VALUE_FIELD.set(callback, callOriginal(callback.thiz(), callback.getArgs()));
            for (HookDef hookDef : hooks)
                if (Hook.TAIL.equals(hookDef.injector)) invoke(hookDef, callback);
            return callback.getReturnValue();
        }

        private Object callOriginal(Object thisObject, Object[] args) throws Throwable {
            try {
                if (backup instanceof Method) return ((Method) backup).invoke(thisObject, args);
                return ((Constructor<?>) backup).newInstance(args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause == null ? e : cause;
            }
        }
    }
}
