package net.bdoyl.localchainsaws;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The chainsaw as a weapon. A normal swing is a vanilla axe hit plus a rev sound and sparks/blood at the impact. Holding
 * right-click anywhere starts the engine: a rev-up and a puff of smoke, {@link #WINDUP} ticks of wind-up, then every
 * {@link #REV_INTERVAL} ticks the chain saws up to {@link #MAX_TARGETS} living things in a narrow {@link #CONE_DEGREES}
 * cone in front of the player within a short {@link #REACH} (shorter than a normal hit). Each one takes slightly more
 * than axe DPS ({@link #REV_DPS_FACTOR}) and costs 1 durability, never below 1 durability. Meanwhile a log of a natural
 * tree under the crosshair within the same reach is sawn into: after {@link #CUT_TICKS} ticks on it, it is broken
 * exactly as a left-click would break it, so the tree fells as usual (see TreeFeller). Letting go winds it down. All server side; the sound and particles are broadcast to
 * everyone nearby, and the rev loop is played by each client for every player it sees revved up (the "using an item"
 * flag is synced vanilla entity data), see ChainsawClient.
 */
public final class ChainsawCombat {
    // ---- Rev attack tuning: everything about how strong holding right-click is lives here. ----
    // Kept short and narrow on purpose (Guy 2026-10-19: "to make it not too op let's limit the range").

    /** Ticks of wind-up after right-click is pressed before the chain saws anything. */
    static final int WINDUP = 12;
    /** Ticks between two sweeps once revved up (4 a second). */
    static final int REV_INTERVAL = 5;
    /** Rev damage per second compared to perfectly timed axe swings (damage x attack speed), for each thing sawn. */
    static final float REV_DPS_FACTOR = 1.1F;
    /** How far the chain reaches, in blocks from the eyes to the nearest point of a hitbox (a normal hit reaches 3). */
    static final double REACH = 2.0;
    /** Width of the area sawn: hitboxes within this many degrees (in total, so half to each side) of where the player looks. */
    static final double CONE_DEGREES = 45.0;
    /** Most things sawn per sweep: the one under the crosshair first, then the nearest. */
    static final int MAX_TARGETS = 3;
    /** Ticks of revved, continuous aim at a log of a natural tree before it is cut through and the tree comes down. */
    static final int CUT_TICKS = 15;
    private static final double CONE_COS = Math.cos(Math.toRadians(CONE_DEGREES / 2));

    static final ResourceKey<DamageType> CHAINSAW_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(LocalChainsaws.MODID, "chainsaw"));
    /** Mobs that get sparks but no blood (skeletons, golems, blazes...). */
    static final TagKey<EntityType<?>> BLOODLESS =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(LocalChainsaws.MODID, "bloodless"));

    private static LivingEntity revTarget; // server thread only: the entity a rev hit is being applied to right now
    private static final Map<ServerPlayer, Cut> CUTS = new WeakHashMap<>(); // server thread only: the log each player is sawing into

    /** A log being sawn into: where, whether it belongs to a natural tree (checked once), and for how many ticks so far. */
    private static final class Cut {
        final BlockPos pos;
        final boolean natural;
        int ticks;

        Cut(BlockPos pos, boolean natural) {
            this.pos = pos;
            this.natural = natural;
        }
    }

    /** Effects per target entity id, only kept while the test scenarios run. */
    static boolean recordEffects;
    static final Map<Integer, int[]> EFFECTS = new HashMap<>(); // {melee hits, rev hits, with blood}
    static final Map<Integer, int[]> ENGINE = new HashMap<>(); // per player id: {rev-ups, revved, rev-downs, logs cut}

    private ChainsawCombat() {}

    /** Right-click with a chainsaw: start the engine (main hand only; a shield in the off hand keeps blocking). */
    static boolean canStartRev(Player player, ItemStack stack, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && hasFuel(stack)
                && !player.getOffhandItem().canPerformAction(ToolActions.SHIELD_BLOCK);
    }

    /** More than 1 durability left: the chainsaw never breaks from revving. */
    static boolean hasFuel(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1;
    }

    /**
     * Right-click pressed (both sides): the rev-up sound, played at once on the player's own client and sent by the
     * server to everyone else nearby, and a puff of smoke from the engine.
     */
    static void revUp(Level level, Player player) {
        level.playSound(player, player.getX(), player.getY(), player.getZ(), LocalChainsaws.CHAINSAW_REV_UP.get(),
                SoundSource.PLAYERS, 0.9F, 1.0F);
        if (level instanceof ServerLevel server) {
            puff(server, player, 5, 0.02);
            if (recordEffects) ENGINE.computeIfAbsent(player.getId(), id -> new int[4])[0]++;
        }
    }

    /** Right-click let go (or the rev stopped): the engine winds down, unless it was only a tap. */
    static void revDown(ServerPlayer player, int ticksUsed) {
        stopCut(player);
        if (ticksUsed < WINDUP / 2) return;
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.getX(), player.getY(), player.getZ(), LocalChainsaws.CHAINSAW_REV_DOWN.get(),
                SoundSource.PLAYERS, 0.9F, 1.0F);
        puff(level, player, 3, 0.01);
        if (recordEffects) ENGINE.computeIfAbsent(player.getId(), id -> new int[4])[2]++;
    }

    /** Called every tick the player holds right-click with a chainsaw (server side). */
    static void revTick(ServerPlayer player, ItemStack stack, int ticksUsed) {
        if (ticksUsed == WINDUP) {
            puff(player.serverLevel(), player, 8, 0.04); // revved up: the exhaust coughs
            if (recordEffects) ENGINE.computeIfAbsent(player.getId(), id -> new int[4])[1]++;
        }
        if (ticksUsed < WINDUP) return;
        if (!hasFuel(stack)) {
            player.stopUsingItem();
            return;
        }
        sawLog(player);
        if (!hasFuel(stack)) { // a felled tree can use it down to 1
            player.stopUsingItem();
            return;
        }
        if ((ticksUsed - WINDUP) % REV_INTERVAL != 0) return;
        LivingEntity last = null;
        for (LivingEntity target : targetsInReach(player)) {
            if (bite(player, stack, target)) last = target;
            if (!hasFuel(stack)) break;
        }
        if (last != null) {
            // Each bite uses up the swing cooldown, so rev-then-swing is no faster than either alone.
            player.resetAttackStrengthTicker();
            player.setLastHurtMob(last);
            player.swing(InteractionHand.MAIN_HAND, true);
        }
        if (!hasFuel(stack)) player.stopUsingItem(); // stops on 1 durability, never breaks
    }

    /**
     * Revved up with a log of a natural tree under the crosshair within {@link #REACH}: saw into it (cracks and sawdust
     * everyone sees), and after {@link #CUT_TICKS} ticks on the same log break it the way a left-click does: mining starts
     * (Tree Harvester's cue when crouching), then the player breaks the block, so felling, caps, drops, replanting,
     * durability and claims all work exactly as for a left-click. Looking away or letting go starts the cut over.
     * Logs of something player-built are left alone: right-click never breaks a build.
     */
    private static void sawLog(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getViewVector(1.0F).scale(REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (hit.getType() != HitResult.Type.BLOCK || !state.is(BlockTags.LOGS) || !level.mayInteract(player, pos)
                || targetInReach(player, REACH) != null) { // a mob in front of the log takes the chain instead
            stopCut(player);
            return;
        }
        Cut cut = CUTS.get(player);
        if (cut == null || !cut.pos.equals(pos)) {
            stopCut(player);
            cut = new Cut(pos.immutable(), TreeFeller.isNaturalTree(level, pos, state));
            CUTS.put(player, cut);
        }
        if (!cut.natural) return;
        cut.ticks++;
        Vec3 at = hit.getLocation();
        if (cut.ticks % 2 == 1) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 4, 0.05, 0.05, 0.05, 0.15);
        if (cut.ticks % REV_INTERVAL == 1) player.swing(InteractionHand.MAIN_HAND, true); // others see it bite into the wood
        if (cut.ticks < CUT_TICKS) {
            level.destroyBlockProgress(crackId(player), pos, cut.ticks * 10 / CUT_TICKS);
            return;
        }
        stopCut(player);
        player.getDigSpeed(state, pos); // mining starts, as with a left-click (Tree Harvester waits for this)
        player.gameMode.destroyBlock(pos);
        if (recordEffects) ENGINE.computeIfAbsent(player.getId(), id -> new int[4])[3]++;
    }

    /** Forget the log being sawn and take its cracks away. */
    private static void stopCut(ServerPlayer player) {
        Cut cut = CUTS.remove(player);
        if (cut != null && cut.ticks > 0) player.serverLevel().destroyBlockProgress(crackId(player), cut.pos, -1);
    }

    /** The cracks need an id of their own: the server doesn't send a player the cracks of their own mining. */
    private static int crackId(Player player) {
        return ~player.getId();
    }

    /** One rev hit on one target; true if it landed (1 durability, food, sparks/blood). */
    private static boolean bite(ServerPlayer player, ItemStack stack, LivingEntity target) {
        if (!target.isAttackable() || target.skipAttackInteraction(player)) return false;
        if (!ForgeHooks.onPlayerAttackTarget(player, target)) return false; // claims / protection mods said no

        float damage = revDamage(player, stack, target);
        ServerLevel level = player.serverLevel();
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(CHAINSAW_DAMAGE), player);
        // Rev hits come faster than the usual 10-tick hurt cooldown: let each one land, and leave the target's cooldown
        // as it was so the rev doesn't shield it from anyone else's hits either.
        int cooldown = target.invulnerableTime;
        target.invulnerableTime = 0;
        revTarget = target;
        boolean hurt;
        try {
            hurt = target.hurt(source, damage);
        } finally {
            revTarget = null;
            target.invulnerableTime = cooldown;
        }
        if (!hurt) return false;
        player.causeFoodExhaustion(0.05F);
        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        impact(level, player, target, true);
        return true;
    }

    /** Damage of one rev hit: (axe damage incl. Sharpness/Smite/Strength) x attack speed x factor / hits per second. */
    static float revDamage(Player player, ItemStack stack, LivingEntity target) {
        double perSwing = player.getAttributeValue(Attributes.ATTACK_DAMAGE) + EnchantmentHelper.getDamageBonus(stack, target.getMobType());
        double swingsPerSecond = player.getAttributeValue(Attributes.ATTACK_SPEED);
        return (float) (perSwing * swingsPerSecond * REV_DPS_FACTOR * REV_INTERVAL / 20.0);
    }

    /**
     * What one sweep saws, at most {@link #MAX_TARGETS}: the mob under the crosshair first, then the nearest living
     * things whose box comes within {@link #REACH} inside the cone in front of the player, not behind a block. Never the
     * player, what they ride or who rides with them, teammates, their own pets, or armor stands; someone else's pet only
     * when aimed at.
     */
    static List<LivingEntity> targetsInReach(Player player) {
        double reach = REACH;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        LivingEntity aimed = targetInReach(player, reach);
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(reach + 1.0),
                e -> e != player && e.isAlive() && !e.isSpectator() && e.isPickable())) {
            if (!maySaw(player, e, e == aimed)) continue;
            if (e == aimed || inCone(player, eye, look, reach, e.getBoundingBox())) found.add(e);
        }
        found.sort(Comparator.<LivingEntity, Boolean>comparing(e -> e != aimed).thenComparingDouble(e -> e.getBoundingBox().distanceToSqr(eye)));
        return found.size() > MAX_TARGETS ? found.subList(0, MAX_TARGETS) : found;
    }

    private static boolean maySaw(Player player, LivingEntity e, boolean aimed) {
        if (e instanceof ArmorStand || e.getRootVehicle() == player.getRootVehicle() || player.isAlliedTo(e)) return false;
        if (e instanceof OwnableEntity pet && pet.getOwnerUUID() != null) return aimed && !pet.getOwnerUUID().equals(player.getUUID());
        return true;
    }

    /**
     * The nearest point of the box is within reach (like a normal hit), the line of sight goes through the box or the
     * part of it nearest the line of sight is inside the cone, and it isn't behind a block.
     */
    private static boolean inCone(Player player, Vec3 eye, Vec3 look, double reach, AABB box) {
        if (box.contains(eye)) return true;
        Vec3 closest = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ));
        if (closest.distanceToSqr(eye) > reach * reach) return false;
        Vec3 center = box.getCenter();
        Vec3 near = box.clip(eye, eye.add(look.scale(reach))).orElse(null);
        if (near == null) {
            Vec3 onSight = eye.add(look.scale(Mth.clamp(center.subtract(eye).dot(look), 0.0, reach)));
            near = new Vec3(Mth.clamp(onSight.x, box.minX, box.maxX), Mth.clamp(onSight.y, box.minY, box.maxY),
                    Mth.clamp(onSight.z, box.minZ, box.maxZ));
            Vec3 to = near.subtract(eye);
            if (to.dot(look) < to.length() * CONE_COS) return false;
        }
        return clear(player, eye, near) || clear(player, eye, center);
    }

    private static boolean clear(Player player, Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    /** Smoke from the engine, at the chainsaw in the player's main hand, seen by everyone nearby. */
    private static void puff(ServerLevel level, Player player, int count, double speed) {
        Vec3 look = player.getViewVector(1.0F);
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        double side = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        Vec3 at = player.getEyePosition().add(look.scale(0.6)).add(-Mth.cos(yaw) * 0.35 * side, -0.45, -Mth.sin(yaw) * 0.35 * side);
        level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, count, 0.06, 0.04, 0.06, speed);
    }

    /** The living entity under the crosshair within {@code reach}, not behind a block. */
    static LivingEntity targetInReach(Player player, double reach) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        AABB box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                EntitySelector.NO_SPECTATORS.and(Entity::isPickable), reach * reach);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity living) || !living.isAlive()) return null;
        HitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS && block.getLocation().distanceToSqr(eye) < hit.getLocation().distanceToSqr(eye)) return null;
        return living;
    }

    /** Rev hits hold the target in the chain instead of knocking it out of reach 4 times a second. */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (revTarget != null && event.getEntity() == revTarget) event.setCanceled(true);
    }

    /** Sound (swings only: the rev has its own loop) and particles where the chain meets the target, sent to everyone nearby. */
    static void impact(ServerLevel level, Player attacker, LivingEntity target, boolean rev) {
        Vec3 at = impactPoint(attacker, target);
        boolean blood = !target.getType().is(BLOODLESS);
        if (!rev) {
            level.playSound(null, at.x, at.y, at.z, LocalChainsaws.CHAINSAW_HIT.get(), SoundSource.PLAYERS, 1.0F,
                    0.9F + level.random.nextFloat() * 0.2F);
        }
        int sparks = (rev ? 3 : 7) * (blood ? 1 : 2);
        level.sendParticles(LocalChainsaws.SPARK.get(), at.x, at.y, at.z, sparks, 0.08, 0.08, 0.08, 0.25);
        if (blood) level.sendParticles(LocalChainsaws.BLOOD.get(), at.x, at.y, at.z, rev ? 3 : 8, 0.12, 0.15, 0.12, 0.12);
        if (recordEffects) {
            int[] n = EFFECTS.computeIfAbsent(target.getId(), id -> new int[3]);
            n[rev ? 1 : 0]++;
            if (blood) n[2]++;
        }
    }

    /** Where the attacker's line of sight enters the target's box, else where the side facing them is, else its middle. */
    static Vec3 impactPoint(Player attacker, LivingEntity target) {
        Vec3 eye = attacker.getEyePosition();
        AABB box = target.getBoundingBox();
        return box.clip(eye, eye.add(attacker.getViewVector(1.0F).scale(8.0)))
                .or(() -> box.clip(eye, box.getCenter())).orElse(box.getCenter());
    }
}
