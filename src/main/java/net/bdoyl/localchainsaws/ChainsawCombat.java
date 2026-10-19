package net.bdoyl.localchainsaws;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The chainsaw as a weapon. A normal swing is a vanilla axe hit plus a rev sound and sparks/blood at the impact. Holding
 * right-click on a mob or player revs the chain into it: a hit every {@link #REV_INTERVAL} ticks worth slightly more
 * than axe DPS in total ({@link #REV_DPS_FACTOR}), 1 durability per hit, never below 1 durability. All server side; the
 * sound and particles are broadcast to everyone nearby, and the rev loop is played by each client for every player it
 * sees using a chainsaw (the "using an item" flag is synced vanilla entity data), see ChainsawClient.
 */
public final class ChainsawCombat {
    /** Ticks between two rev hits (4 a second). */
    static final int REV_INTERVAL = 5;
    /** Rev damage per second compared to perfectly timed axe swings (damage x attack speed). */
    static final float REV_DPS_FACTOR = 1.1F;

    static final ResourceKey<DamageType> CHAINSAW_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(LocalChainsaws.MODID, "chainsaw"));
    /** Mobs that get sparks but no blood (skeletons, golems, blazes...). */
    static final TagKey<EntityType<?>> BLOODLESS =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(LocalChainsaws.MODID, "bloodless"));

    private static LivingEntity revTarget; // server thread only: the entity a rev hit is being applied to right now

    /** Effects per target entity id, only kept while the test scenarios run. */
    static boolean recordEffects;
    static final Map<Integer, int[]> EFFECTS = new HashMap<>(); // {melee hits, rev hits, with blood}

    private ChainsawCombat() {}

    /** Right-click on a living entity: start revving (main hand only; a shield in the off hand keeps blocking). */
    static boolean canStartRev(Player player, ItemStack stack, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && hasFuel(stack)
                && !player.getOffhandItem().canPerformAction(ToolActions.SHIELD_BLOCK);
    }

    /** More than 1 durability left: the chainsaw never breaks from revving. */
    static boolean hasFuel(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1;
    }

    /** Called every tick the player holds right-click with a chainsaw (server side). */
    static void revTick(ServerPlayer player, ItemStack stack, int ticksUsed) {
        if (ticksUsed <= 0 || ticksUsed % REV_INTERVAL != 0) return;
        if (!hasFuel(stack)) {
            player.stopUsingItem();
            return;
        }
        LivingEntity target = targetInReach(player);
        if (target == null || !target.isAttackable() || target.skipAttackInteraction(player)) return;
        if (!ForgeHooks.onPlayerAttackTarget(player, target)) return; // claims / protection mods said no

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
        // Each bite uses up the swing cooldown, so rev-then-swing is no faster than either alone.
        player.resetAttackStrengthTicker();
        if (!hurt) return;
        player.setLastHurtMob(target);
        player.causeFoodExhaustion(0.05F);
        player.swing(InteractionHand.MAIN_HAND, true);
        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        impact(level, player, target, true);
    }

    /** Damage of one rev hit: (axe damage incl. Sharpness/Smite/Strength) x attack speed x factor / hits per second. */
    static float revDamage(Player player, ItemStack stack, LivingEntity target) {
        double perSwing = player.getAttributeValue(Attributes.ATTACK_DAMAGE) + EnchantmentHelper.getDamageBonus(stack, target.getMobType());
        double swingsPerSecond = player.getAttributeValue(Attributes.ATTACK_SPEED);
        return (float) (perSwing * swingsPerSecond * REV_DPS_FACTOR * REV_INTERVAL / 20.0);
    }

    /** The living entity under the crosshair within attack reach, not behind a block. */
    static LivingEntity targetInReach(Player player) {
        double reach = player.getEntityReach();
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

    /** Where the attacker's line of sight enters the target's box, else the middle of it. */
    static Vec3 impactPoint(Player attacker, LivingEntity target) {
        Vec3 eye = attacker.getEyePosition();
        AABB box = target.getBoundingBox();
        return box.clip(eye, eye.add(attacker.getViewVector(1.0F).scale(8.0))).orElse(box.getCenter());
    }
}
