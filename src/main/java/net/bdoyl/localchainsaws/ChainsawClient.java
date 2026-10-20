package net.bdoyl.localchainsaws;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side of {@link ChainsawCombat}: the rev loop (for every player this client sees revving, itself included; it
 * comes in as the server-sent rev-up sound reaches the top), the spark and blood particles, and the chainsaw being
 * brought up during the wind-up and shaking once revved.
 *
 * With -Dlocalchainsaws.clientcheck=true it also logs "[localchainsaws-clientcheck] ..." lines for every chainsaw sound
 * the client plays and the particles it spawns, for the headless client test.
 */
public final class ChainsawClient {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws-clientcheck");
    private static final boolean CHECK = Boolean.getBoolean("localchainsaws.clientcheck");
    private static final Map<Integer, RevSound> REVVING = new HashMap<>(); // entity id -> its rev loop
    private static int sparks, blood, logTimer;

    private ChainsawClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(ChainsawClient::registerParticles);
        MinecraftForge.EVENT_BUS.addListener(ChainsawClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ChainsawClient::onPlaySound);
    }

    static boolean isRevving(LivingEntity entity) {
        return entity.isUsingItem() && entity.getUseItem().getItem() instanceof ChainsawItem;
    }

    private static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(LocalChainsaws.SPARK.get(), SparkParticle.Provider::new);
        event.registerSpriteSet(LocalChainsaws.BLOOD.get(), BloodParticle.Provider::new);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            REVVING.clear();
            return;
        }
        REVVING.values().removeIf(AbstractTickableSoundInstance::isStopped);
        for (Player player : level.players()) {
            if (!isRevving(player) || REVVING.containsKey(player.getId())) continue;
            RevSound sound = new RevSound(player);
            REVVING.put(player.getId(), sound);
            mc.getSoundManager().play(sound);
            if (CHECK) LOG.info("[localchainsaws-clientcheck] rev loop started for {} ({})", player.getGameProfile().getName(),
                    player == mc.player ? "this player" : "another player");
        }
        if (CHECK && ++logTimer >= 20) {
            logTimer = 0;
            if (sparks + blood > 0) LOG.info("[localchainsaws-clientcheck] particles spawned in the last second: {} sparks, {} blood", sparks, blood);
            sparks = blood = 0;
        }
    }

    /** Our one-shot sounds (rev-up/down, hits, felling) at this computer's soundVolume (client config); the rev loop has its own. */
    private static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || !sound.getLocation().getNamespace().equals(LocalChainsaws.MODID)) return;
        if (CHECK) {
            LOG.info("[localchainsaws-clientcheck] sound {} at {} {} {}", sound.getLocation(),
                    Math.round(sound.getX()), Math.round(sound.getY()), Math.round(sound.getZ()));
        }
        float factor = (float) ChainsawConfig.soundVolume();
        if (factor < 1.0F && !(sound instanceof RevSound)) event.setSound(factor <= 0.0F ? null : new Quieter(sound, factor));
    }

    /** A sound played as is, only quieter. */
    private record Quieter(SoundInstance sound, float factor) implements SoundInstance {
        @Override public ResourceLocation getLocation() { return sound.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager manager) { return sound.resolve(manager); }
        @Override public Sound getSound() { return sound.getSound(); }
        @Override public SoundSource getSource() { return sound.getSource(); }
        @Override public boolean isLooping() { return sound.isLooping(); }
        @Override public boolean isRelative() { return sound.isRelative(); }
        @Override public int getDelay() { return sound.getDelay(); }
        @Override public float getVolume() { return sound.getVolume() * factor; }
        @Override public float getPitch() { return sound.getPitch(); }
        @Override public double getX() { return sound.getX(); }
        @Override public double getY() { return sound.getY(); }
        @Override public double getZ() { return sound.getZ(); }
        @Override public Attenuation getAttenuation() { return sound.getAttenuation(); }
        @Override public boolean canStartSilent() { return sound.canStartSilent(); }
        @Override public boolean canPlaySound() { return sound.canPlaySound(); }
        @Override public java.util.concurrent.CompletableFuture<net.minecraft.client.sounds.AudioStream> getStream(
                net.minecraft.client.sounds.SoundBufferLibrary buffers, Sound s, boolean looping) {
            return sound.getStream(buffers, s, looping);
        }
    }

    /** How far along the wind-up a revving player is: 0 just pressed, 1 revved up (sawing). */
    static float windup(LivingEntity entity, float partialTick) {
        int windup = ChainsawConfig.windupTicks();
        return windup <= 0 ? 1.0F : Mth.clamp((entity.getTicksUsingItem() + partialTick) / windup, 0.0F, 1.0F);
    }

    /**
     * The engine held at high revs while a player holds right-click with a revved-up chainsaw: silent during the
     * wind-up (the rev-up sound plays then), fades in as that one tops out, follows the player, fades out on release
     * under the rev-down sound.
     */
    static final class RevSound extends AbstractTickableSoundInstance {
        private final Player player;
        private int fade;
        private boolean logged;

        RevSound(Player player) {
            super(LocalChainsaws.CHAINSAW_REV.get(), SoundSource.PLAYERS, player.getRandom());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F;
            this.pitch = 1.0F;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
        }

        @Override
        public boolean canStartSilent() {
            return true; // starts at volume 0 and ramps up
        }

        @Override
        public void tick() {
            if (player.isRemoved()) {
                stop();
                return;
            }
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            if (isRevving(player)) {
                // In over the last 3 ticks of the wind-up, as the rev-up sound reaches the loop's revs.
                int in = player.getTicksUsingItem() - (ChainsawConfig.windupTicks() - 3);
                volume = Mth.clamp(in * 0.25F, 0.0F, 0.9F) * (float) ChainsawConfig.revLoopVolume();
                if (CHECK && volume > 0 && !logged) {
                    logged = true;
                    LOG.info("[localchainsaws-clientcheck] revved up: rev loop audible for {} after {} ticks", player.getGameProfile().getName(),
                            player.getTicksUsingItem());
                }
            } else if (++fade > 3) {
                stop();
            } else {
                volume *= 0.4F;
                pitch *= 0.94F;
            }
        }
    }

    /** Hot metal flying off the chain: bright, falls, fades orange to red. */
    static final class SparkParticle extends TextureSheetParticle {
        private final SpriteSet sprites;

        SparkParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.xd = xd * 1.4;
            this.yd = yd * 1.4 + 0.08;
            this.zd = zd * 1.4;
            this.gravity = 0.9F;
            this.friction = 0.92F;
            this.lifetime = 6 + random.nextInt(6);
            this.quadSize = 0.06F + random.nextFloat() * 0.04F;
            setColor(1.0F, 0.9F, 0.55F);
            setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            setSpriteFromAge(sprites);
            float t = (float) age / lifetime;
            setColor(1.0F, Mth.lerp(t, 0.9F, 0.35F), Mth.lerp(t, 0.55F, 0.1F));
            alpha = 1.0F - t * t;
        }

        @Override
        protected int getLightColor(float partialTick) {
            return 0xF000F0; // glows
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xd, double yd, double zd) {
                sparks++;
                return new SparkParticle(level, x, y, z, xd, yd, zd, sprites);
            }
        }
    }

    /** Small dark red droplets that fall and settle, then shrink away. */
    static final class BloodParticle extends TextureSheetParticle {
        BloodParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = xd;
            this.yd = yd + 0.04;
            this.zd = zd;
            this.gravity = 1.0F;
            this.friction = 0.96F;
            this.lifetime = 18 + random.nextInt(14);
            this.quadSize = 0.04F + random.nextFloat() * 0.045F;
            float shade = 0.45F + random.nextFloat() * 0.2F;
            setColor(shade, 0.02F, 0.03F);
            pickSprite(sprites);
        }

        @Override
        public float getQuadSize(float partialTick) {
            float left = (lifetime - age - partialTick) / 6.0F;
            return quadSize * Mth.clamp(left, 0.0F, 1.0F);
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }

        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xd, double yd, double zd) {
                blood++;
                return new BloodParticle(level, x, y, z, xd, yd, zd, sprites);
            }
        }
    }

    /**
     * First person: during the wind-up the chainsaw is brought up and forward with a growing judder, then held out
     * shaking with the engine while it saws (others see it bite: a swing per sweep that hits).
     */
    static final IClientItemExtensions ITEM_EXTENSIONS = new IClientItemExtensions() {
        @Override
        public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float partialTick,
                float equipProgress, float swingProgress) {
            InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            if (!isRevving(player) || player.getUsedItemHand() != hand) return false;
            float t = player.tickCount + partialTick;
            float up = windup(player, partialTick);
            float ease = up * up * (3 - 2 * up);
            float shake = up < 1.0F ? 0.004F + 0.006F * up : 0.014F;
            int side = arm == HumanoidArm.RIGHT ? 1 : -1;
            // Vanilla's resting hand position, raised and pushed forward over the wind-up, plus the engine judder.
            pose.translate(side * (0.56F - 0.04F * ease) + Mth.sin(t * 2.9F) * shake, -0.52F + 0.08F * ease + Mth.cos(t * 3.7F) * shake,
                    -0.72F - 0.08F * ease);
            pose.mulPose(Axis.XP.rotationDegrees(-6.0F * ease));
            return true;
        }
    };
}
