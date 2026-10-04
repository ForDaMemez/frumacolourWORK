package com.sri.frumacolor.mixin;

import com.sri.frumacolor.FrumaColorClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

    @Inject(method = "makeParticle", at = @At("RETURN"))
    private void frumacolor$tint(ParticleOptions options, double x, double y, double z,
                                 double xa, double ya, double za,
                                 CallbackInfoReturnable<Particle> cir) {
        Particle p = cir.getReturnValue();
        if (p != null) {
            FrumaColorClient.onParticle(options, p, x, y, z);
        }
    }
}
