package net.bdoyl.localchainsaws;

import com.mojang.blaze3d.vertex.PoseStack;
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
 * Client side of {@link ChainsawCombat}: the rev loop (for every player this client sees revving, itself included),
 * the spark and blood particles, and the shaking hold while revving.
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
        if (CHECK) MinecraftForge.EVENT_BUS.addListener(ChainsawClient::onPlaySound);
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

    private static void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() != null && event.getSound().getLocation().getNamespace().equals(LocalChainsaws.MODID)) {
            LOG.info("[localchainsaws-clientcheck] sound {} at {} {} {}", event.getSound().getLocation(),
                    Math.round(event.getSound().getX()), Math.round(event.getSound().getY()), Math.round(event.getSound().getZ()));
        }
    }

    /** The engine held at high revs while a player holds right-click with a chainsaw; follows them, fades out on release. */
    static final class RevSound extends AbstractTickableSoundInstance {
        private final Player player;
        private int age, fade;

        RevSound(Player player) {
            super(LocalChainsaws.CHAINSAW_REV.get(), SoundSource.PLAYERS, player.getRandom());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F;
            this.pitch = 0.8F;
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
                age++;
                volume = Math.min(0.9F, 0.25F + age * 0.08F);
                pitch = Math.min(1.1F, 0.8F + age * 0.04F); // spins up over ~8 ticks
            } else if (++fade > 4) {
                stop();
            } else {
                volume *= 0.55F;
                pitch *= 0.95F;
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

    /** While revving, the chainsaw shakes with the engine in first person (others see it bite: a swing per hit). */
    static final IClientItemExtensions ITEM_EXTENSIONS = new IClientItemExtensions() {
        @Override
        public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float partialTick,
                float equipProgress, float swingProgress) {
            InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            if (!isRevving(player) || player.getUsedItemHand() != hand) return false;
            float t = player.tickCount + partialTick;
            int side = arm == HumanoidArm.RIGHT ? 1 : -1;
            // Vanilla's resting hand position, pushed forward a little, plus a fast engine judder.
            pose.translate(side * 0.56F + Mth.sin(t * 2.9F) * 0.012F, -0.52F + Mth.cos(t * 3.7F) * 0.012F, -0.78F);
            return true;
        }
    };
}
