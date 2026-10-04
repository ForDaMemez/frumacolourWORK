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
    @Shadow NativeImage[] byMipLevel;

    // runs when a texture is created: just records its name for /fcolor find
    @Inject(method = "<init>", at = @At("TAIL"))
    private void frumacolor$seen(CallbackInfo ci) {
        String name = String.valueOf(((SpriteContents) (Object) this).name());
        FrumaColorClient.onSprite(name, this.originalImage);
    }

    // runs right before the texture is uploaded to the GPU: this is where the recolor is applied
    @Inject(method = "uploadFirstFrame", at = @At("HEAD"))
    private void frumacolor$upload(CallbackInfo ci) {
        String name = String.valueOf(((SpriteContents) (Object) this).name());
        FrumaColorClient.onUpload(name, this.byMipLevel);
    }
}
