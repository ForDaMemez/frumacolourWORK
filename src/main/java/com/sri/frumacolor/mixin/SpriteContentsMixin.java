package com.sri.frumacolor.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.sri.frumacolor.FrumaColorClient;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {

    @Shadow @Final private NativeImage originalImage;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void frumacolor$recolor(CallbackInfo ci) {
        String name = String.valueOf(((SpriteContents) (Object) this).name());
        FrumaColorClient.onSprite(name, this.originalImage);
    }
}
