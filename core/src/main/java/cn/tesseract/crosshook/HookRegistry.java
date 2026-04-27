package cn.tesseract.crosshook;

import java.lang.reflect.Constructor;

public interface HookRegistry {
    HookRegistry instance = getInstance();

    private static HookRegistry getInstance() {
        try {
            Constructor ctor = Class.forName("cn.tesseract.crosshook.HookRegistryImpl").getConstructor();
            return (HookRegistry) ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Unable to create hook registry instance", e);
        }
    }

    void register(String hookClass);
}
