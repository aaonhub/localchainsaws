package net.bdoyl.localchainsaws;

import com.mojang.authlib.GameProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grow a real tree, break its bottom log with a chainsaw held by a fake player, count what drops. Each scenario runs in
 * a {@link TestArena}: as GameTests in dev ({@link ChainsawGameTests}, ./gradlew runGameTestServer) or inside a full
 * modpack server with -Dlocalchainsaws.selftest=true ({@link SelfTest}).
 */
final class ChainsawScenarios {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws-test");
    private static final BlockPos BASE = new BlockPos(10, 1, 10);

    /** Every scenario by name, with the ticks it may take. */
    record Scenario(Consumer<TestArena> run, int maxTicks) {}

    static final Map<String, Scenario> ALL = new LinkedHashMap<>();
    static {
        ALL.put("iron_fells_oak", new Scenario(ChainsawScenarios::ironFellsOak, 100));
        ALL.put("iron_fells_birch", new Scenario(ChainsawScenarios::ironFellsBirch, 100));
        ALL.put("diamond_fells_spruce", new Scenario(ChainsawScenarios::diamondFellsSpruce, 100));
        ALL.put("diamond_fells_acacia", new Scenario(ChainsawScenarios::diamondFellsAcacia, 100));
        ALL.put("diamond_fells_dark_oak", new Scenario(ChainsawScenarios::diamondFellsDarkOak, 100));
        ALL.put("netherite_fells_giant_jungle", new Scenario(ChainsawScenarios::netheriteFellsGiantJungle, 100));
        ALL.put("iron_stops_at_its_cap", new Scenario(ChainsawScenarios::ironStopsAtItsCap, 100));
        ALL.put("sneaking_leaves_it_to_tree_harvester", new Scenario(ChainsawScenarios::sneakingLeavesItToTreeHarvester, 200));
        ALL.put("ignores_player_built_logs", new Scenario(ChainsawScenarios::ignoresPlayerBuiltLogs, 100));
        ALL.put("stops_before_the_tool_breaks", new Scenario(ChainsawScenarios::stopsBeforeTheToolBreaks, 100));
        ALL.put("replanted_sapling_survives_a_held_swing", new Scenario(ChainsawScenarios::replantedSaplingSurvivesAHeldSwing, 140));
        ALL.put("neighbour_tree_keeps_its_leaves", new Scenario(ChainsawScenarios::neighbourTreeKeepsItsLeaves, 100));
        // Fighting with it (ChainsawCombat).
        ALL.put("swing_deals_axe_damage_with_effects", new Scenario(ChainsawScenarios::swingDealsAxeDamageWithEffects, 40));
        ALL.put("rev_winds_up_before_sawing", new Scenario(ChainsawScenarios::revWindsUpBeforeSawing, 80));
        ALL.put("rev_slightly_beats_axe_dps", new Scenario(ChainsawScenarios::revSlightlyBeatsAxeDps, 100));
        ALL.put("rev_saws_everything_in_front", new Scenario(ChainsawScenarios::revSawsEverythingInFront, 80));
        ALL.put("rev_saws_at_most_three_at_once", new Scenario(ChainsawScenarios::revSawsAtMostThreeAtOnce, 60));
        ALL.put("rev_stops_at_one_durability", new Scenario(ChainsawScenarios::revStopsAtOneDurability, 100));
        ALL.put("rev_starts_anywhere_and_breaks_no_blocks", new Scenario(ChainsawScenarios::revStartsAnywhereAndBreaksNoBlocks, 80));
        ALL.put("felling_still_works_after_revving", new Scenario(ChainsawScenarios::fellingStillWorksAfterRevving, 100));
        // Revved into a tree (ChainsawCombat.sawLog).
        ALL.put("rev_fells_a_tree_and_saws_mobs_meanwhile", new Scenario(ChainsawScenarios::revFellsATreeAndSawsMobsMeanwhile, 100));
        ALL.put("rev_cut_needs_continuous_aim", new Scenario(ChainsawScenarios::revCutNeedsContinuousAim, 100));
        ALL.put("rev_cut_respects_the_cap", new Scenario(ChainsawScenarios::revCutRespectsTheCap, 100));
        ALL.put("rev_cut_never_breaks_the_saw", new Scenario(ChainsawScenarios::revCutNeverBreaksTheSaw, 100));
        ALL.put("rev_leaves_player_built_logs_alone", new Scenario(ChainsawScenarios::revLeavesPlayerBuiltLogsAlone, 100));
        ALL.put("rev_cut_while_sneaking_leaves_it_to_tree_harvester", new Scenario(ChainsawScenarios::revCutWhileSneakingLeavesItToTreeHarvester, 200));
        // 1.3.0: wear warnings, advancements, config, netherite edge cases, other mods failing.
        ALL.put("wear_warns_when_low_and_when_worn_out", new Scenario(ChainsawScenarios::wearWarnsWhenLowAndWhenWornOut, 40));
        ALL.put("felling_grants_the_timber_advancement", new Scenario(ChainsawScenarios::fellingGrantsTheTimberAdvancement, 100));
        ALL.put("config_changes_apply_at_once", new Scenario(ChainsawScenarios::configChangesApplyAtOnce, 60));
        ALL.put("netherite_fortune_silk_touch_fell_normally", new Scenario(ChainsawScenarios::netheriteFortuneSilkTouchFellNormally, 100));
        ALL.put("smithing_keeps_enchantments_and_wear", new Scenario(ChainsawScenarios::smithingKeepsEnchantmentsAndWear, 40));
        ALL.put("another_mod_failing_on_a_rev_kill_does_not_crash", new Scenario(ChainsawScenarios::anotherModFailingOnARevKillDoesNotCrash, 60));
        ALL.put("another_mod_failing_mid_felling_does_not_crash", new Scenario(ChainsawScenarios::anotherModFailingMidFellingDoesNotCrash, 100));
    }

    private ChainsawScenarios() {}

    /** A fake player whose swing is fully charged and whose stats include the held tool (fake players never tick). */
    static final class Fighter extends FakePlayer {
        Fighter(ServerLevel level, String name, ItemStack tool) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
            setItemInHand(InteractionHand.MAIN_HAND, tool);
            var modifiers = com.google.common.collect.ImmutableMultimap.<Holder<net.minecraft.world.entity.ai.attributes.Attribute>,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier>builder();
            tool.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, modifiers::put);
            getAttributes().addTransientAttributeModifiers(modifiers.build());
        }

        void charge() {
            attackStrengthTicker = 100;
        }

        /** Right-click with nothing in the way (the real server path for right-click in the air or on a mob that has no use of its own). */
        boolean rev() {
            return gameMode.useItem(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND).consumesAction() && isUsingItem();
        }

        /** Right-click on a block: the block and the item get it first (the real server path); only if neither uses it, the item is used. */
        InteractionResult rightClickBlock(BlockHitResult hit) {
            InteractionResult r = gameMode.useItemOn(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            return r.consumesAction() ? r : gameMode.useItem(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND);
        }

        /** Holding right-click for {@code ticks} ticks: the item-use update LivingEntity.tick runs (fake players never tick). */
        void holdUse(int ticks) {
            for (int i = 0; i < ticks && isUsingItem(); i++) updateUsingItem(getUseItem());
        }

        /** Look towards {@code yaw} (0 = south), {@code pitch} degrees down. */
        void face(float yaw, float pitch) {
            setYRot(yaw);
            setYHeadRot(yaw);
            setXRot(pitch);
        }
    }

    private static Fighter fighter(TestArena h, String name, ItemStack tool) {
        ChainsawCombat.recordEffects = true;
        Fighter p = new Fighter(h.level(), name, tool);
        BlockPos at = h.abs(BASE);
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** A still mob ({@code NoAI}) 2 blocks in front of the fighter, who looks at its middle. */
    private static <T extends Mob> T dummy(TestArena h, EntityType<T> type, Fighter p) {
        T mob = type.create(h.level());
        BlockPos at = h.abs(BASE.offset(0, 0, 2));
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        h.level().addFreshEntity(mob);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, mob.position().add(0, mob.getBbHeight() * 0.6, 0));
        return mob;
    }

    /** A still mob ({@code NoAI}) with its feet at ({@code dx}, {@code dz}) from the fighter's feet. */
    private static <T extends Mob> T mobAt(TestArena h, EntityType<T> type, Fighter p, double dx, double dz) {
        T mob = type.create(h.level());
        mob.moveTo(p.getX() + dx, p.getY(), p.getZ() + dz, 180, 0);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        h.level().addFreshEntity(mob);
        return mob;
    }

    private static boolean hurt(LivingEntity e) {
        return e.getHealth() < e.getMaxHealth();
    }

    private static int[] engine(Fighter p) {
        return ChainsawCombat.ENGINE.getOrDefault(p.getId(), new int[3]);
    }

    private static int[] effects(LivingEntity target) {
        return ChainsawCombat.EFFECTS.getOrDefault(target.getId(), new int[3]);
    }

    /** A full swing does exactly the axe's damage, costs the axe's 2 durability, and sends the rev sound + sparks + blood. */
    static void swingDealsAxeDamageWithEffects(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-swing", tool);
        Pig pig = dummy(h, EntityType.PIG, p);
        float axe = (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        p.charge();
        p.attack(pig);
        float dealt = pig.getMaxHealth() - pig.getHealth();
        int[] fx = effects(pig);
        LOG.info("[localchainsaws-test] swing: axe damage {}, pig took {}, durability used {}, effects melee={} rev={} blood={}",
                axe, dealt, tool.getDamageValue(), fx[0], fx[1], fx[2]);
        h.check(axe == 9.0F, "an iron chainsaw should hit like an iron axe (9), has " + axe);
        h.check(Math.abs(dealt - axe) < 0.01F, "the pig should take " + axe + ", took " + dealt);
        h.check(tool.getDamageValue() == 2, "a swing should cost 2 durability like an axe, cost " + tool.getDamageValue());
        h.check(fx[0] == 1 && fx[1] == 0 && fx[2] == 1, "expected one melee impact with blood");
        h.pass();
    }

    /**
     * Pressing right-click starts the engine (rev-up, smoke) but nothing is sawn for the {@link ChainsawConfig#windupTicks}
     * ticks of wind-up; the first bite comes on the tick it is revved up. Letting go winds it down, and pressing again
     * starts a new wind-up.
     */
    static void revWindsUpBeforeSawing(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-windup", tool);
        IronGolem golem = dummy(h, EntityType.IRON_GOLEM, p);
        h.check(p.rev(), "right-click should start the engine");
        h.check(engine(p)[0] == 1, "pressing right-click should rev up (sound + smoke)");
        p.holdUse(ChainsawConfig.windupTicks());
        boolean quietDuringWindup = !hurt(golem) && tool.getDamageValue() == 0 && engine(p)[1] == 0;
        p.holdUse(1);
        float bite = golem.getMaxHealth() - golem.getHealth();
        LOG.info("[localchainsaws-test] wind-up: {} ticks without a bite: {}, then bite {} at tick {}, durability {}",
                ChainsawConfig.windupTicks(), quietDuringWindup, bite, p.getTicksUsingItem() - 1, tool.getDamageValue());
        h.check(quietDuringWindup, "nothing should be sawn during the wind-up");
        h.check(bite > 0 && tool.getDamageValue() == 1 && engine(p)[1] == 1, "the first bite should land the tick the saw is revved up");
        p.releaseUsingItem();
        h.check(!p.isUsingItem() && engine(p)[2] == 1, "letting go should stop the saw and wind it down");

        float health = golem.getHealth();
        h.check(p.rev(), "right-click again should restart the engine");
        p.holdUse(ChainsawConfig.windupTicks());
        h.check(golem.getHealth() == health && tool.getDamageValue() == 1, "a new press should wind up again before sawing");
        p.releaseUsingItem();
        h.check(p.rev(), "and again");
        p.holdUse(3);
        p.releaseUsingItem();
        h.check(engine(p)[2] == 2, "a short tap should not play the wind-down");
        h.pass();
    }

    /**
     * Revved up on an iron golem (100 health, no knockback, no blood) for 2 s: 8 bites that together beat perfectly
     * timed axe swings by about 10% (not a one-shot), 1 durability each.
     */
    static void revSlightlyBeatsAxeDps(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev", tool);
        IronGolem golem = dummy(h, EntityType.IRON_GOLEM, p);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 1);
        float first = golem.getMaxHealth() - golem.getHealth();
        p.holdUse(40);
        float dealt = golem.getMaxHealth() - golem.getHealth() - first;
        double axeDps = p.getAttributeValue(Attributes.ATTACK_DAMAGE) * p.getAttributeValue(Attributes.ATTACK_SPEED);
        double revDps = dealt / 2.0;
        int[] fx = effects(golem);
        LOG.info("[localchainsaws-test] rev: 2 s on an iron golem after the first bite -> {} damage ({} per s, axe {} per s, x{}), durability used {}, effects rev={} blood={}",
                dealt, revDps, axeDps, String.format("%.2f", revDps / axeDps), tool.getDamageValue(), fx[1], fx[2]);
        h.check(revDps >= axeDps * 1.0 && revDps <= axeDps * 1.25, "rev DPS " + revDps + " should be 1.0-1.25x axe DPS " + axeDps);
        h.check(first < p.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.35, "one rev hit should be a small bite, was " + first);
        h.check(golem.isAlive() && golem.getHealth() > 50, "the golem should still be standing");
        h.check(tool.getDamageValue() == 9, "9 rev hits should cost 9 durability, cost " + tool.getDamageValue());
        h.check(fx[1] == 9 && fx[2] == 0, "expected 9 rev impacts without blood on a golem, got rev=" + fx[1] + " blood=" + fx[2]);
        p.releaseUsingItem();
        h.check(!p.isUsingItem(), "letting go of right-click should stop the rev");
        h.pass();
    }

    /** Looking south at the middle of a pig 1.6 blocks ahead (the layout below), as a player aiming at it would. */
    private static final float PITCH_AT_PIG = 36.2F;

    /**
     * One sweep saws what is in the narrow cone within the chain's 3-block reach (two pigs just ahead and one 2.65 blocks
     * off), and nothing behind, off to the side, wider than the cone, beyond 3 blocks, behind a wall, the player's own
     * wolf or an armor stand. Each pig sawn costs 1 durability.
     */
    static void revSawsEverythingInFront(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-sweep", tool);
        p.face(0, PITCH_AT_PIG); // south, +z
        Pig ahead = mobAt(h, EntityType.PIG, p, 0, 1.6), right = mobAt(h, EntityType.PIG, p, -0.95, 1.5);
        Pig behind = mobAt(h, EntityType.PIG, p, 0, -1.6), side = mobAt(h, EntityType.PIG, p, 1.9, 0);
        Pig wide = mobAt(h, EntityType.PIG, p, 1.3, 1.2); // 27 degrees off: inside a 60 degree cone, not a 45
        Pig mid = mobAt(h, EntityType.PIG, p, 0, 3.0); // 2.65 blocks: within the 3-block reach
        Pig far = mobAt(h, EntityType.PIG, p, 0, 4.0); // 3.62 blocks: beyond it
        Pig walled = mobAt(h, EntityType.PIG, p, -2.0, 0);
        for (int y = 0; y < 3; y++) h.setBlock(BASE.offset(-1, y + 1, 0), Blocks.STONE);
        Wolf wolf = mobAt(h, EntityType.WOLF, p, 0.35, 0.75);
        wolf.setTame(true, true);
        wolf.setOwnerUUID(p.getUUID());
        ArmorStand stand = EntityType.ARMOR_STAND.create(h.level());
        stand.moveTo(p.getX() - 0.35, p.getY(), p.getZ() + 0.75);
        h.level().addFreshEntity(stand);

        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 1);
        List<Pig> sawn = List.of(ahead, right, mid);
        boolean allSawn = sawn.stream().allMatch(ChainsawScenarios::hurt) && sawn.stream().allMatch(e -> effects(e)[1] == 1);
        boolean spared = !hurt(behind) && !hurt(side) && !hurt(wide) && !hurt(far) && !hurt(walled) && !hurt(wolf) && stand.isAlive();
        int cost = tool.getDamageValue();
        LOG.info("[localchainsaws-test] sweep: ahead/right/2.65 blocks sawn {}, behind/side/wide/far/walled/own wolf/armor stand spared {} (wide {} far {}), durability {}",
                allSawn, spared, hurt(wide) ? "HURT" : "spared", hurt(far) ? "HURT" : "spared", cost);
        h.check(allSawn, "both pigs just ahead and the one 2.65 blocks off should be sawn once");
        h.check(!hurt(wide), "a pig 27 degrees off should be outside the 45 degree cone");
        h.check(!hurt(far), "a pig 3.62 blocks away should be beyond the chain's 3-block reach");
        h.check(spared, "nothing behind, beside, out of reach, behind a wall, the own wolf or the armor stand should be hurt");
        h.check(cost == 3, "3 pigs sawn should cost 3 durability, cost " + cost);

        // Turn to the walled pig: the wall stops the chain; take the wall away and it bites.
        p.face(90, 30.3F); // west, -x, at the walled pig's middle
        p.holdUse(ChainsawConfig.revIntervalTicks());
        h.check(!hurt(walled) && tool.getDamageValue() == 3, "the chain should not saw through a wall");
        for (int y = 0; y < 3; y++) h.setBlock(BASE.offset(-1, y + 1, 0), Blocks.AIR);
        p.holdUse(ChainsawConfig.revIntervalTicks());
        h.check(hurt(walled) && tool.getDamageValue() == 4, "without the wall the pig should be sawn");
        p.releaseUsingItem();
        h.pass();
    }

    /** Five pigs crowded right in front: one sweep saws only {@link ChainsawConfig#maxTargets} of them (3 durability). */
    static void revSawsAtMostThreeAtOnce(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-crowd", tool);
        p.face(0, PITCH_AT_PIG);
        List<Pig> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) crowd.add(mobAt(h, EntityType.PIG, p, -0.2 + 0.1 * i, 1.6));
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 1);
        long sawn = crowd.stream().filter(ChainsawScenarios::hurt).count();
        LOG.info("[localchainsaws-test] crowd: {} of 5 pigs in reach sawn in one sweep, durability {}", sawn, tool.getDamageValue());
        h.check(sawn == ChainsawConfig.maxTargets(), "one sweep should saw " + ChainsawConfig.maxTargets() + " of the 5 pigs, sawed " + sawn);
        h.check(tool.getDamageValue() == ChainsawConfig.maxTargets(), "and cost " + ChainsawConfig.maxTargets() + " durability, cost " + tool.getDamageValue());
        p.holdUse(ChainsawConfig.revIntervalTicks());
        h.check(tool.getDamageValue() == 2 * ChainsawConfig.maxTargets(), "the next sweep saws " + ChainsawConfig.maxTargets() + " again");
        p.releaseUsingItem();
        h.pass();
    }

    /** A worn chainsaw saws until it has 1 durability left, even mid-sweep, then stops by itself and won't start again. */
    static void revStopsAtOneDurability(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 3);
        Fighter p = fighter(h, "lcs-rev-worn", tool);
        p.face(0, PITCH_AT_PIG);
        List<Pig> pigs = List.of(mobAt(h, EntityType.PIG, p, 0, 1.6), mobAt(h, EntityType.PIG, p, 0.95, 1.5), mobAt(h, EntityType.PIG, p, -0.95, 1.5));
        for (Pig pig : pigs) {
            pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            pig.setHealth(200);
        }
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 30);
        int left = tool.getMaxDamage() - tool.getDamageValue();
        int bites = pigs.stream().mapToInt(e -> effects(e)[1]).sum(), bloody = pigs.stream().mapToInt(e -> effects(e)[2]).sum();
        long sawn = pigs.stream().filter(ChainsawScenarios::hurt).count();
        LOG.info("[localchainsaws-test] worn rev: {} durability left, {} bites on {} of 3 pigs, still revving {}", left, bites, sawn, p.isUsingItem());
        h.check(!tool.isEmpty() && left == 1, "the chainsaw should end on 1 durability, has " + left);
        h.check(bites == 2 && bloody == 2 && sawn == 2, "expected 2 bloody bites before it stopped, got " + bites);
        h.check(!p.isUsingItem(), "the rev should stop by itself at 1 durability");
        h.check(pigs.stream().allMatch(e -> e.getDeltaMovement().horizontalDistance() < 1.0E-6), "rev hits should not knock the pigs back");
        h.check(!p.rev(), "a chainsaw on 1 durability should not rev");
        h.pass();
    }

    /**
     * Right-click revs from anywhere: in the air (a pig 5 blocks off is out of reach), or on a block without a use of
     * its own, which stays put. On a log it still strips it instead, and with a shield in the off hand right-click
     * keeps blocking.
     */
    static void revStartsAnywhereAndBreaksNoBlocks(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-air", tool);
        p.face(0, 0);
        Pig far = mobAt(h, EntityType.PIG, p, 0, 5);
        h.check(p.rev(), "right-click in the air should start the engine");
        p.holdUse(ChainsawConfig.windupTicks() + 20);
        h.check(p.isUsingItem() && !hurt(far) && tool.getDamageValue() == 0, "revving at nothing should hurt nothing and cost nothing");
        p.releaseUsingItem();

        BlockPos log = BASE.offset(1, 1, 0), stone = BASE.offset(-1, 1, 0);
        h.setBlock(log, Blocks.OAK_LOG);
        h.setBlock(stone, Blocks.STONE);
        InteractionResult onLog = p.rightClickBlock(new BlockHitResult(Vec3.atCenterOf(h.abs(log)), Direction.WEST, h.abs(log), false));
        h.check(onLog.consumesAction() && h.getBlockState(log).is(Blocks.STRIPPED_OAK_LOG) && !p.isUsingItem(),
                "right-click on a log should strip it, not rev");
        int stripCost = tool.getDamageValue(); // stripping costs 1 durability, like any axe
        InteractionResult onStone = p.rightClickBlock(new BlockHitResult(Vec3.atCenterOf(h.abs(stone)), Direction.EAST, h.abs(stone), false));
        h.check(onStone.consumesAction() && p.isUsingItem(), "right-click on stone should start the engine");
        p.holdUse(ChainsawConfig.windupTicks() + 20);
        h.check(h.getBlockState(stone).is(Blocks.STONE) && h.getBlockState(log).is(Blocks.STRIPPED_OAK_LOG), "revving should not break blocks");
        h.check(tool.getDamageValue() == stripCost, "revving at blocks should cost no durability");
        p.releaseUsingItem();

        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        h.check(!p.rev(), "with a shield in the off hand the rev should not start");
        h.pass();
    }

    /** Revving a mob doesn't change felling: afterwards a left-click on the oak still brings the whole tree down. */
    static void fellingStillWorksAfterRevving(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE.offset(3, 0, 0), 13);
        int logs = countLogs(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-fell", tool);
        IronGolem golem = dummy(h, EntityType.IRON_GOLEM, p);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.revIntervalTicks() + 1);
        p.releaseUsingItem();
        int revCost = tool.getDamageValue();
        golem.discard();
        BlockPos at = h.abs(BASE.offset(3, 0, 0));
        p.getDigSpeed(h.level().getBlockState(at), at);
        h.check(p.gameMode.destroyBlock(at), "the player could not break the log");
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] fell after rev: {} logs grown -> {} dropped, {} left, durability rev {} + felling {}",
                    logs, got, countLogs(h), revCost, tool.getDamageValue() - revCost);
            h.check(revCost == 2, "2 rev hits should cost 2 durability, cost " + revCost);
            h.check(got == logs && countLogs(h) == 0, "the whole oak should come down after revving, dropped " + got + "/" + logs);
            h.check(tool.getDamageValue() - revCost == logs, "felling should cost 1 durability per log");
            h.pass();
        });
    }

    /** Where the trees for the rev cut tests stand: 1.5 blocks in front (south) of the fighter, within the chain's reach. */
    private static final BlockPos TRUNK = BASE.offset(0, 0, 2);

    /** Aim at the near face of the log at {@code rel}, a little above its middle, as a player sawing into it would. */
    private static void aimAt(TestArena h, Fighter p, BlockPos rel) {
        BlockPos at = h.abs(rel);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(at.getX() + 0.5, at.getY() + 0.6, at.getZ()));
    }

    private static boolean isLog(TestArena h, BlockPos rel) {
        return h.getBlockState(rel).is(BlockTags.LOGS);
    }

    /**
     * Revved with the crosshair on an oak's trunk 1.5 blocks away: nothing happens to it during the wind-up and the first
     * {@link ChainsawConfig#cutTicks} - 1 revved ticks, then the whole tree comes down exactly as from a left-click (all
     * logs as items, leaves gone, sapling replanted, 1 durability per log), while a pig beside the trunk, inside the
     * cone, is sawn all the while.
     */
    static void revFellsATreeAndSawsMobsMeanwhile(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, TRUNK, 13);
        int logs = countLogs(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-tree", tool);
        aimAt(h, p, TRUNK);
        Pig pig = mobAt(h, EntityType.PIG, p, -0.9, 1.0);
        // The real path: right-click on the trunk itself revs into it, it doesn't strip it.
        BlockPos at = h.abs(TRUNK);
        InteractionResult onTrunk = p.rightClickBlock(new BlockHitResult(new Vec3(at.getX() + 0.5, at.getY() + 0.6, at.getZ()), Direction.NORTH, at, false));
        h.check(onTrunk.consumesAction() && p.isUsingItem(), "right-click on a tree's trunk should start the rev");
        h.check(h.getBlockState(TRUNK).is(Blocks.OAK_LOG), "right-click on a tree's trunk should not strip it");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks() - 1);
        boolean standing = isLog(h, TRUNK);
        p.holdUse(1);
        boolean cut = !isLog(h, TRUNK);
        int bites = effects(pig)[1];
        p.releaseUsingItem();
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] rev into an oak: still standing 1 tick before the cut {}, cut on time {}, {} logs grown -> {} dropped, "
                    + "{} logs / {} leaves left, stump {}, pig bitten {} times, durability {}", standing, cut, logs, got, countLogs(h), countLeaves(h),
                    h.getBlockState(TRUNK).getBlock(), bites, tool.getDamageValue());
            h.check(standing, "the log should hold until the chain has been in it for " + ChainsawConfig.cutTicks() + " ticks");
            h.check(cut, "the log should be cut after " + ChainsawConfig.cutTicks() + " revved ticks on it");
            h.check(got == logs && countLogs(h) == 0 && countLeaves(h) == 0, "the whole oak should come down as items, dropped " + got + "/" + logs);
            h.check(h.getBlockState(TRUNK).is(BlockTags.SAPLINGS) || dropped(h, Items.OAK_SAPLING) == 0, "a dropped sapling should be replanted");
            h.check(bites == 3 && hurt(pig), "the pig in the cone should be bitten every 5 ticks meanwhile (3 times), was " + bites);
            h.check(tool.getDamageValue() == logs + bites, "1 durability per log and per bite: expected " + (logs + bites) + ", got " + tool.getDamageValue());
            h.pass();
        });
    }

    /** The cut starts over when the player lets go or looks away, like mining a block. */
    static void revCutNeedsContinuousAim(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, TRUNK, 14);
        int logs = countLogs(h);
        ItemStack tool = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-aim", tool);
        aimAt(h, p, TRUNK);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks() - 1);
        p.releaseUsingItem();
        h.check(isLog(h, TRUNK), "1 tick short of the cut time the log should stand");
        h.check(p.rev(), "right-click again");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks() - 1);
        h.check(isLog(h, TRUNK), "letting go should have started the cut over");
        p.face(90, 0); // a glance to the side for one tick
        p.holdUse(1);
        aimAt(h, p, TRUNK);
        p.holdUse(ChainsawConfig.cutTicks() - 1);
        h.check(isLog(h, TRUNK), "looking away should have started the cut over");
        p.holdUse(1);
        h.check(!isLog(h, TRUNK), "after a full uninterrupted cut the log should go");
        p.releaseUsingItem();
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] rev cut aim: {} logs grown -> {} dropped, durability {}", logs, got, tool.getDamageValue());
            h.check(got == logs && countLogs(h) == 0, "the whole oak should come down, dropped " + got + "/" + logs);
            h.check(tool.getDamageValue() == logs, "1 durability per log, no bites: expected " + logs + ", got " + tool.getDamageValue());
            h.pass();
        });
    }

    /** Revving into a 96-log trunk with an iron chainsaw takes 64 from the bottom, like a left-click. */
    static void revCutRespectsTheCap(TestArena h) {
        floor(h);
        bigTrunk(h, TRUNK);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-cap", tool);
        aimAt(h, p, TRUNK);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks());
        p.releaseUsingItem();
        h.later(5, () -> {
            int got = dropped(h, Items.JUNGLE_LOG);
            LOG.info("[localchainsaws-test] rev cap: 96 logs, iron chainsaw revved -> {} dropped, {} left, damage {}", got, countLogs(h), tool.getDamageValue());
            h.check(got == 64 && countLogs(h) == 32, "iron should stop at 64 logs, dropped " + got);
            h.check(tool.getDamageValue() == 64, "expected 64 damage, got " + tool.getDamageValue());
            h.pass();
        });
    }

    /** A nearly worn chainsaw revved into a tree cuts what it can, ends on 1 durability and stops revving. */
    static void revCutNeverBreaksTheSaw(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, TRUNK, 9);
        ItemStack tool = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 4);
        Fighter p = fighter(h, "lcs-rev-worn-tree", tool);
        aimAt(h, p, TRUNK);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks() + 20);
        boolean revving = p.isUsingItem();
        p.releaseUsingItem();
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG), left = tool.getMaxDamage() - tool.getDamageValue();
            LOG.info("[localchainsaws-test] rev worn on a tree: {} logs dropped, {} durability left, still revving {}", got, left, revving);
            h.check(!tool.isEmpty() && left == 1, "the chainsaw should end on 1 durability, has " + left);
            h.check(got == 3, "expected 3 logs (2 extra + the cut one), got " + got);
            h.check(!revving, "the rev should stop by itself at 1 durability");
            h.pass();
        });
    }

    /** A log column with player-placed leaves is a build: revving into it for 2 s leaves it whole and costs nothing. */
    static void revLeavesPlayerBuiltLogsAlone(TestArena h) {
        floor(h);
        for (int y = 1; y <= 5; y++) h.setBlock(TRUNK.atY(y), Blocks.OAK_LOG);
        BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 6; y++) {
            BlockPos p = TRUNK.offset(dx, 0, dz).atY(y);
            if (!h.getBlockState(p).is(Blocks.OAK_LOG)) h.setBlock(p, placed);
        }
        ItemStack tool = new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-build", tool);
        aimAt(h, p, TRUNK);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 40);
        p.releaseUsingItem();
        LOG.info("[localchainsaws-test] rev at a build: {} of 5 logs left, durability {}", countLogs(h), tool.getDamageValue());
        h.check(countLogs(h) == 5 && dropped(h, Items.OAK_LOG) == 0, "revving should not cut into a player-built log column");
        h.check(tool.getDamageValue() == 0, "and cost nothing, cost " + tool.getDamageValue());
        h.pass();
    }

    /** Revving into a tree while sneaking: the same split as a left-click (Tree Harvester fells it once, else one log). */
    static void revCutWhileSneakingLeavesItToTreeHarvester(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, TRUNK, 8);
        int logs = countLogs(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-rev-sneak", tool);
        p.setShiftKeyDown(true);
        p.setPose(net.minecraft.world.entity.Pose.CROUCHING); // a fake player never ticks its pose
        aimAt(h, p, TRUNK);
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + ChainsawConfig.cutTicks());
        p.releaseUsingItem();
        boolean th = ModList.get().isLoaded("treeharvester");
        h.later(100, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] rev while sneaking (treeharvester loaded: {}): {} logs grown -> {} dropped, {} left", th, logs, got, countLogs(h));
            h.check(got <= logs, "more logs dropped than the tree had: something felled it twice");
            if (th) h.check(got == logs, "Tree Harvester should fell the whole tree when sneaking, got " + got + "/" + logs);
            else h.check(got == 1, "sneaking should chop just one log, got " + got);
            h.pass();
        });
    }

    /** 1.21: enchantments are data-driven, so they are looked up in the world's registry. */
    private static Holder<Enchantment> enchantment(TestArena h, ResourceKey<Enchantment> key) {
        return h.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    private static void floor(TestArena h) {
        for (int x = 0; x < 21; x++) for (int z = 0; z < 21; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
    }

    private static void grow(TestArena h, ResourceKey<ConfiguredFeature<?, ?>> tree, BlockPos rel, long seed) {
        ServerLevel level = h.level();
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(tree);
        boolean ok = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), h.abs(rel));
        h.check(ok, "tree " + tree.location() + " did not generate");
    }

    private static int countBlocks(TestArena h, Predicate<BlockState> what) {
        int n = 0;
        for (int x = 0; x < 21; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 21; z++)
            if (what.test(h.getBlockState(new BlockPos(x, y, z)))) n++;
        return n;
    }

    private static int countLogs(TestArena h) {
        return countBlocks(h, s -> s.is(BlockTags.LOGS));
    }

    private static int countLeaves(TestArena h) {
        return countBlocks(h, s -> s.is(BlockTags.LEAVES));
    }

    private static int dropped(TestArena h, Item item) {
        AABB box = new AABB(Vec3.atLowerCornerOf(h.abs(BlockPos.ZERO)), Vec3.atLowerCornerOf(h.abs(new BlockPos(21, 40, 21)))).inflate(2);
        List<ItemEntity> items = h.level().getEntitiesOfClass(ItemEntity.class, box);
        return items.stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static FakePlayer player(TestArena h, String name, ItemStack tool, boolean sneaking) {
        FakePlayer p = FakePlayerFactory.get(h.level(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
        BlockPos at = h.abs(BASE.offset(2, 0, 0));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
        p.setShiftKeyDown(sneaking);
        p.setPose(sneaking ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING); // a fake player never ticks its pose
        return p;
    }

    private static void cut(TestArena h, FakePlayer p, BlockPos rel) {
        h.check(tryCut(h, p, rel), "the player could not break the log");
    }

    /** False when something cancelled the break (Tree Harvester does, as it breaks the logs itself). */
    private static boolean tryCut(TestArena h, FakePlayer p, BlockPos rel) {
        BlockPos at = h.abs(rel);
        // Like a real player: mining starts (BreakSpeed event, which Tree Harvester uses to record a harvest attempt),
        // then the block breaks.
        p.getDigSpeed(h.level().getBlockState(at), at);
        return p.gameMode.destroyBlock(at);
    }

    /** Whole tree felled, every log dropped as an item, no leaves left, 1 durability per log, sapling replanted if one dropped. */
    static void fellsWholeTree(TestArena h, ResourceKey<ConfiguredFeature<?, ?>> tree, Item saw, Item logItem, long seed) {
        floor(h);
        grow(h, tree, BASE, seed);
        int logs = countLogs(h), leaves = countLeaves(h);
        int cap = ((ChainsawItem) saw).maxLogs();
        ItemStack tool = new ItemStack(saw);
        cut(h, player(h, "lcs-" + tree.location().getPath(), tool, false), BASE);
        h.later(5, () -> {
            int expect = Math.min(logs, cap);
            int got = dropped(h, logItem), logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            BlockState stump = h.getBlockState(BASE);
            LOG.info("[localchainsaws-test] {} {}: {} logs + {} leaves grown -> {} logs dropped, {} logs / {} leaves left, damage {}, stump {}",
                    saw, tree.location().getPath(), logs, leaves, got, logsLeft, leavesLeft, tool.getDamageValue(), stump.getBlock());
            h.check(got == expect, "expected " + expect + " " + logItem + " items, got " + got);
            h.check(logsLeft == logs - expect, "expected " + (logs - expect) + " logs left, found " + logsLeft);
            if (logs <= cap) h.check(leavesLeft == 0, leavesLeft + " leaves left behind");
            h.check(tool.getDamageValue() == expect, "expected " + expect + " damage, got " + tool.getDamageValue());
            boolean replanted = stump.is(BlockTags.SAPLINGS);
            int planted = countBlocks(h, st -> st.is(BlockTags.SAPLINGS));
            LOG.info("[localchainsaws-test] {}: {} sapling(s) planted", tree.location().getPath(), planted);
            h.check(planted <= 4, planted + " saplings planted");
            int saplingsOnGround = h.level().getEntitiesOfClass(ItemEntity.class,
                    new AABB(Vec3.atLowerCornerOf(h.abs(BlockPos.ZERO)), Vec3.atLowerCornerOf(h.abs(new BlockPos(21, 40, 21)))).inflate(2))
                    .stream().filter(e -> e.getItem().is(net.minecraft.tags.ItemTags.SAPLINGS)).mapToInt(e -> e.getItem().getCount()).sum();
            h.check(replanted || saplingsOnGround == 0, "saplings dropped but none was replanted");
            h.pass();
        });
    }

    static void ironFellsOak(TestArena h) {
        fellsWholeTree(h, TreeFeatures.OAK, LocalChainsaws.IRON_CHAINSAW.get(), Items.OAK_LOG, 1);
    }

    static void ironFellsBirch(TestArena h) {
        fellsWholeTree(h, TreeFeatures.BIRCH, LocalChainsaws.IRON_CHAINSAW.get(), Items.BIRCH_LOG, 2);
    }

    static void diamondFellsSpruce(TestArena h) {
        fellsWholeTree(h, TreeFeatures.SPRUCE, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.SPRUCE_LOG, 3);
    }

    static void diamondFellsAcacia(TestArena h) {
        fellsWholeTree(h, TreeFeatures.ACACIA, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.ACACIA_LOG, 4);
    }

    static void diamondFellsDarkOak(TestArena h) {
        fellsWholeTree(h, TreeFeatures.DARK_OAK, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.DARK_OAK_LOG, 5);
    }

    static void netheriteFellsGiantJungle(TestArena h) {
        fellsWholeTree(h, TreeFeatures.MEGA_JUNGLE_TREE, LocalChainsaws.NETHERITE_CHAINSAW.get(), Items.JUNGLE_LOG, 6);
    }

    /** A 2x2 trunk of 96 logs: the iron chainsaw takes 64 from the bottom (1 durability each) and leaves the rest standing. */
    static void ironStopsAtItsCap(TestArena h) {
        floor(h);
        bigTrunk(h, BASE);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        cut(h, player(h, "lcs-cap", tool, false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.JUNGLE_LOG);
            LOG.info("[localchainsaws-test] cap: 96 logs, iron chainsaw dropped {}, {} left, damage {}", got, countLogs(h), tool.getDamageValue());
            h.check(got == 64, "iron should stop at 64 logs, dropped " + got);
            h.check(countLogs(h) == 32, "expected 32 logs left, found " + countLogs(h));
            h.check(tool.getDamageValue() == 64, "expected 64 damage, got " + tool.getDamageValue());
            h.pass();
        });
    }

    /** A 2x2 jungle trunk 24 logs tall (96 logs) with a natural canopy, its corner at {@code at}. */
    private static void bigTrunk(TestArena h, BlockPos at) {
        BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (int y = 1; y <= 24; y++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) h.setBlock(at.offset(dx, 0, dz).atY(y), Blocks.JUNGLE_LOG);
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
            BlockPos p = at.offset(dx, 0, dz).atY(25);
            h.setBlock(p, leaf);
            if (dx < 0 || dx > 1 || dz < 0 || dz > 1) h.setBlock(p.below(), leaf);
        }
    }

    /** Sneaking: the chainsaw does nothing special. With Tree Harvester installed it fells the tree instead (once, not twice). */
    static void sneakingLeavesItToTreeHarvester(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 8);
        int logs = countLogs(h);
        tryCut(h, player(h, "lcs-sneak", new ItemStack(LocalChainsaws.IRON_CHAINSAW.get()), true), BASE);
        boolean th = ModList.get().isLoaded("treeharvester");
        h.later(100, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] sneaking (treeharvester loaded: {}): {} logs grown -> {} dropped, {} left", th, logs, got, countLogs(h));
            h.check(got <= logs, "more logs dropped than the tree had: something felled it twice");
            if (th) h.check(got == logs, "Tree Harvester should fell the whole tree when sneaking, got " + got + "/" + logs);
            else h.check(got == 1, "sneaking should chop just one log, got " + got);
            h.pass();
        });
    }

    /** Logs with player-placed (persistent) leaves are a build, not a tree: only the broken log drops. */
    static void ignoresPlayerBuiltLogs(TestArena h) {
        floor(h);
        for (int y = 1; y <= 5; y++) h.setBlock(BASE.atY(y), Blocks.OAK_LOG);
        BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 6; y++) {
            BlockPos p = BASE.offset(dx, 0, dz).atY(y);
            if (!h.getBlockState(p).is(Blocks.OAK_LOG)) h.setBlock(p, placed);
        }
        cut(h, player(h, "lcs-build", new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get()), false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            h.check(got == 1, "a player-built log column should lose one log, dropped " + got);
            h.check(countLogs(h) == 4, "expected 4 logs left, found " + countLogs(h));
            h.pass();
        });
    }

    /** A nearly broken chainsaw cuts what it can and stops with 1 durability left instead of breaking. */
    static void stopsBeforeTheToolBreaks(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 9);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 4);
        cut(h, player(h, "lcs-worn", tool, false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] worn chainsaw: {} logs dropped, {} durability left", got, tool.getMaxDamage() - tool.getDamageValue());
            h.check(!tool.isEmpty() && tool.getMaxDamage() - tool.getDamageValue() == 1, "chainsaw should end on 1 durability");
            h.check(got == 3, "expected 3 logs (2 extra + the cut one), got " + got);
            h.pass();
        });
    }

    /** Felling one tree leaves its neighbour, and the neighbour's leaves, standing. */
    static void neighbourTreeKeepsItsLeaves(TestArena h) {
        floor(h);
        BlockPos other = BASE.offset(4, 0, 0);
        grow(h, TreeFeatures.OAK, BASE.offset(-1, 0, 0), 10);
        int before = countLogs(h);
        grow(h, TreeFeatures.OAK, other, 11);
        int otherLogs = countLogs(h) - before;
        cut(h, player(h, "lcs-neighbour", new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get()), false), BASE.offset(-1, 0, 0));
        h.later(5, () -> {
            int logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            LOG.info("[localchainsaws-test] neighbour: {} logs felled, {} logs / {} leaves left", dropped(h, Items.OAK_LOG), logsLeft, leavesLeft);
            h.check(logsLeft == otherLogs, "the other tree should keep all " + otherLogs + " logs, found " + logsLeft);
            h.check(leavesLeft >= 20, "the other tree lost its leaves (" + leavesLeft + " left)");
            h.pass();
        });
    }

    /**
     * A fancy oak (~200 leaves, so a sapling nearly always drops) is replanted; the player's chainsaw, still swinging,
     * can't knock the new sapling out during the grace period, but can afterwards.
     */
    static void replantedSaplingSurvivesAHeldSwing(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.FANCY_OAK, BASE, 12);
        FakePlayer p = player(h, "lcs-replant", new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get()), false);
        cut(h, p, BASE);
        h.later(3, () -> {
            if (!h.getBlockState(BASE).is(BlockTags.SAPLINGS)) {
                h.check(dropped(h, Items.OAK_SAPLING) == 0, "a sapling dropped but was not replanted");
                LOG.info("[localchainsaws-test] replant: no sapling dropped this time, nothing to check");
                h.pass();
                return;
            }
            h.check(!tryCut(h, p, BASE), "the chainsaw broke the fresh sapling during the grace period");
            h.check(h.getBlockState(BASE).is(BlockTags.SAPLINGS), "the sapling is gone");
            h.later(TreeFeller.SAPLING_GRACE_TICKS + 5, () -> {
                h.check(tryCut(h, p, BASE), "after the grace period the sapling should break normally");
                LOG.info("[localchainsaws-test] replant: sapling kept during the grace period, breakable after");
                h.pass();
            });
        });
    }

    // ---- 1.3.0 ----

    /**
     * The chainsaw in hand dropping to the low mark (10% by default) warns once; further wear stays quiet until the last
     * point, which warns again ("worn out"). Switching to an already worn chainsaw, or repairing one, is quiet.
     */
    static void wearWarnsWhenLowAndWhenWornOut(TestArena h) {
        ChainsawWear.record = true;
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-wear", tool);
        int mark = ChainsawWear.lowMark(tool);
        h.check(mark == (int) Math.ceil(tool.getMaxDamage() * ChainsawConfig.lowDurabilityPercent() / 100.0), "low mark " + mark);
        tool.setDamageValue(tool.getMaxDamage() - mark - 1);
        ChainsawWear.check(p);
        int[] w = ChainsawWear.WARNINGS.computeIfAbsent(p.getId(), id -> new int[2]);
        h.check(w[0] == 0 && w[1] == 0, "a chainsaw above the mark should not warn");
        tool.setDamageValue(tool.getDamageValue() + 1);
        ChainsawWear.check(p);
        h.check(w[0] == 1, "dropping to the low mark (" + mark + " left) should warn once, warned " + w[0]);
        tool.setDamageValue(tool.getDamageValue() + 3);
        ChainsawWear.check(p);
        h.check(w[0] == 1, "more wear below the mark should stay quiet");
        tool.setDamageValue(tool.getMaxDamage() - 1);
        ChainsawWear.check(p);
        h.check(w[1] == 1, "the last point should warn 'worn out', warned " + w[1]);
        ItemStack worn = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        worn.setDamageValue(worn.getMaxDamage() - 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, worn);
        ChainsawWear.check(p);
        worn.setDamageValue(worn.getMaxDamage() - 5); // repaired a bit
        ChainsawWear.check(p);
        h.check(w[0] == 1 && w[1] == 1, "switching to a worn chainsaw or repairing it should be quiet");
        LOG.info("[localchainsaws-test] wear: mark {} of {}, warnings low={} worn-out={}", mark, tool.getMaxDamage(), w[0], w[1]);
        h.pass();
    }

    /** A felled tree grants "Timber!" (to real players; fake players, i.e. other mods' machines, are skipped). */
    static void fellingGrantsTheTimberAdvancement(TestArena h) {
        TreeFeller.recordAwards = true;
        var advancements = h.level().getServer().getAdvancements();
        for (String id : List.of("craft_chainsaw", "fell_tree")) {
            h.check(advancements.get(LocalChainsaws.id(id)) != null,
                    "advancement localchainsaws:" + id + " is missing");
        }
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 21);
        int before = TreeFeller.AWARDS.getOrDefault("fell_tree", 0);
        FakePlayer p = player(h, "lcs-timber", new ItemStack(LocalChainsaws.IRON_CHAINSAW.get()), false);
        cut(h, p, BASE);
        h.check(TreeFeller.AWARDS.getOrDefault("fell_tree", 0) == before + 1, "felling should try to grant fell_tree once");
        h.check(!TreeFeller.award(p, "fell_tree", "felled"), "a fake player must not get advancements");
        h.pass();
    }

    /** Server config values take effect on the next use: maxTargets 1 saws one of five crowded pigs, then back to the default. */
    static void configChangesApplyAtOnce(TestArena h) {
        h.check(ChainsawConfig.SERVER_SPEC.isLoaded(), "the server config should be loaded");
        h.check(ChainsawConfig.maxTargets() == ChainsawConfig.MAX_TARGETS && ChainsawConfig.reach() == ChainsawConfig.REACH,
                "expected the default maxTargets/reach, have " + ChainsawConfig.maxTargets() + "/" + ChainsawConfig.reach());
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-config", tool);
        p.face(0, PITCH_AT_PIG);
        List<Pig> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) crowd.add(mobAt(h, EntityType.PIG, p, -0.2 + 0.1 * i, 1.6));
        long sawn;
        // Set and restored within this one call, so scenarios running at the same time never see the changed value.
        ChainsawConfig.setMaxTargets(1);
        try {
            h.check(p.rev(), "right-click should start the rev");
            p.holdUse(ChainsawConfig.windupTicks() + 1);
            sawn = crowd.stream().filter(ChainsawScenarios::hurt).count();
            p.releaseUsingItem();
        } finally {
            ChainsawConfig.setMaxTargets(ChainsawConfig.MAX_TARGETS);
        }
        LOG.info("[localchainsaws-test] config: maxTargets 1 -> {} of 5 pigs sawn, back to {}", sawn, ChainsawConfig.maxTargets());
        h.check(sawn == 1, "with maxTargets = 1 one sweep should saw 1 pig, sawed " + sawn);
        h.check(ChainsawConfig.maxTargets() == ChainsawConfig.MAX_TARGETS, "maxTargets should be back to the default");
        h.pass();
    }

    /**
     * Fortune and Silk Touch don't change what a felling drops from logs (vanilla logs ignore both): one log item per
     * log, leaves drop their decay loot, and the enchantments stay on the netherite chainsaw.
     */
    static void netheriteFortuneSilkTouchFellNormally(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 22);
        grow(h, TreeFeatures.BIRCH, BASE.offset(-6, 0, -6), 23);
        int oak = countBlocks(h, st -> st.is(Blocks.OAK_LOG)), birch = countBlocks(h, st -> st.is(Blocks.BIRCH_LOG));
        Holder<Enchantment> fortuneEnch = enchantment(h, Enchantments.FORTUNE), silkEnch = enchantment(h, Enchantments.SILK_TOUCH);
        ItemStack fortune = new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get());
        fortune.enchant(fortuneEnch, 3);
        ItemStack silk = new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get());
        silk.enchant(silkEnch, 1);
        cut(h, player(h, "lcs-fortune", fortune, false), BASE);
        cut(h, player(h, "lcs-silk", silk, false), BASE.offset(-6, 0, -6));
        h.later(5, () -> {
            int gotOak = dropped(h, Items.OAK_LOG), gotBirch = dropped(h, Items.BIRCH_LOG);
            int leafBlocks = dropped(h, Items.OAK_LEAVES) + dropped(h, Items.BIRCH_LEAVES);
            LOG.info("[localchainsaws-test] fortune III: {}/{} oak logs, silk touch: {}/{} birch logs, {} leaf blocks dropped, damage {} / {}",
                    gotOak, oak, gotBirch, birch, leafBlocks, fortune.getDamageValue(), silk.getDamageValue());
            h.check(gotOak == oak, "Fortune III should drop exactly one item per log: " + gotOak + "/" + oak);
            h.check(gotBirch == birch, "Silk Touch should drop exactly one item per log: " + gotBirch + "/" + birch);
            h.check(leafBlocks == 0, "leaves should drop decay loot, not themselves (" + leafBlocks + " leaf blocks)");
            h.check(fortune.getDamageValue() == oak && silk.getDamageValue() == birch, "1 durability per log");
            h.check(EnchantmentHelper.getItemEnchantmentLevel(fortuneEnch, fortune) == 3
                    && EnchantmentHelper.getItemEnchantmentLevel(silkEnch, silk) == 1, "enchantments kept");
            h.pass();
        });
    }

    /** Upgrading at a smithing table keeps the diamond chainsaw's enchantments, name and wear (like vanilla tools). */
    static void smithingKeepsEnchantmentsAndWear(TestArena h) {
        ServerLevel level = h.level();
        Holder<Enchantment> efficiency = enchantment(h, Enchantments.EFFICIENCY), unbreaking = enchantment(h, Enchantments.UNBREAKING);
        ItemStack diamond = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        diamond.enchant(efficiency, 5);
        diamond.enchant(unbreaking, 3);
        diamond.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Old Faithful"));
        diamond.setDamageValue(100);
        SmithingRecipeInput table = new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), diamond,
                new ItemStack(Items.NETHERITE_INGOT));
        var recipe = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMITHING, table, level);
        h.check(recipe.isPresent(), "no smithing recipe for template + diamond chainsaw + netherite ingot");
        ItemStack out = recipe.get().value().assemble(table, level.registryAccess());
        LOG.info("[localchainsaws-test] smithing: {} -> {} (damage {}, enchantments {})", diamond, out, out.getDamageValue(), out.getEnchantments());
        h.check(out.is(LocalChainsaws.NETHERITE_CHAINSAW.get()), "the result should be a netherite chainsaw, is " + out);
        h.check(EnchantmentHelper.getItemEnchantmentLevel(efficiency, out) == 5
                && EnchantmentHelper.getItemEnchantmentLevel(unbreaking, out) == 3, "enchantments lost");
        h.check(out.getHoverName().getString().equals("Old Faithful"), "name lost");
        h.check(out.getDamageValue() == 100, "wear should carry over (100), has " + out.getDamageValue());
        h.pass();
    }

    /** Entities whose death makes the test listener throw, standing in for another mod's bug (2026-10-20 crash). */
    static final String THROW_ON_DEATH = "localchainsaws_test_throw_on_death";
    /** Logs whose break event makes the test listener throw. */
    static final java.util.Set<BlockPos> THROW_ON_BREAK = new java.util.HashSet<>();
    private static boolean failingListeners;

    private static void addFailingListeners() {
        if (failingListeners) return;
        failingListeners = true;
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) -> {
            if (e.getEntity().getTags().contains(THROW_ON_DEATH)) throw new IllegalStateException("test: another mod failing on a death");
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) -> {
            if (THROW_ON_BREAK.contains(e.getPos())) throw new IllegalStateException("test: another mod failing on a block break");
        });
    }

    /** Another mod throwing while reacting to a rev kill stops the rev and is logged; the server keeps running. */
    static void anotherModFailingOnARevKillDoesNotCrash(TestArena h) {
        addFailingListeners();
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-failing-kill", tool);
        Pig pig = dummy(h, EntityType.PIG, p);
        pig.setHealth(1.0F);
        pig.addTag(THROW_ON_DEATH);
        int before = Guard.failures("rev");
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawConfig.windupTicks() + 1); // first bite kills it, the listener throws inside hurt()
        LOG.info("[localchainsaws-test] failing mod on a rev kill: guard caught {}, still revving {}, pig health {}",
                Guard.failures("rev") - before, p.isUsingItem(), pig.getHealth());
        h.check(Guard.failures("rev") == before + 1, "the failure should be caught once, caught " + (Guard.failures("rev") - before));
        h.check(!p.isUsingItem(), "the rev should stop after a failure");
        h.check(pig.getHealth() <= 0, "the pig should still have died");
        h.pass();
    }

    /** Another mod throwing on one of our per-log breaks ends the felling there; the cut log still breaks, no crash. */
    static void anotherModFailingMidFellingDoesNotCrash(TestArena h) {
        addFailingListeners();
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 24);
        int logs = countLogs(h);
        BlockPos bomb = h.abs(BASE.above(2));
        THROW_ON_BREAK.add(bomb);
        int before = Guard.failures("felling");
        boolean broke;
        try {
            broke = tryCut(h, player(h, "lcs-failing-felling", new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get()), false), BASE);
        } finally {
            THROW_ON_BREAK.remove(bomb);
        }
        int caught = Guard.failures("felling") - before;
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] failing mod mid-felling: guard caught {}, cut log broke {}, {} of {} logs dropped", caught, broke, got, logs);
            h.check(caught == 1, "the failure should be caught once, caught " + caught);
            h.check(broke && h.getBlockState(BASE).isAir() || h.getBlockState(BASE).is(BlockTags.SAPLINGS), "the cut log should still break");
            h.check(got >= 1 && got < logs, "the felling should stop at the failing log, dropped " + got + "/" + logs);
            h.pass();
        });
    }
}
