package cn.tesseract.moddedpixeldungeon;

import cn.tesseract.crosshook.HookRegistry;
import cn.tesseract.moddedpixeldungeon.hook.CommonHook;

public class ModdedPixelDungeon {
    public static void init() {
        HookRegistry.instance.register(CommonHook.class.getName());
    }
}
