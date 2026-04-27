package cn.tesseract.moddedpixeldungeon.hook;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoMob;
import com.watabou.noosa.audio.Sample;

import cn.tesseract.crosshook.Callback;
import cn.tesseract.crosshook.Hook;

public class CommonHook {
    @Hook(injector = Hook.TAIL, targetMethod = "<init>")
    public static void init(Callback<WndInfoMob> c, Mob mob) {
        final WndInfoMob thiz = c.getThisObject();
        int btnWidth = thiz.getWidth() / 4 - 1;

        RedButton btnTaunt = new RedButton("嘲讽") {
            protected void onClick() {
                mob.beckon(Dungeon.hero.pos);
                Sample.INSTANCE.play(Assets.Sounds.MIMIC);
                thiz.hide();
            }
        };
        btnTaunt.setRect(0, thiz.getHeight() + 2, btnWidth, 16);
        thiz.add(btnTaunt);

        RedButton btnPush = new RedButton("推") {
            protected void onClick() {
                thiz.hide();
            }
        };
        btnPush.setRect(btnTaunt.right() + 1, thiz.getHeight() + 2, btnWidth, 16);
        thiz.add(btnPush);

        thiz.resize(thiz.getWidth(), (int) btnPush.bottom() + 2);
    }
}
