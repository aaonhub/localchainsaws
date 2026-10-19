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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
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
        ALL.put("rev_stops_at_one_durability", new Scenario(ChainsawScenarios::revStopsAtOneDurability, 100));
        ALL.put("rev_starts_anywhere_and_breaks_no_blocks", new Scenario(ChainsawScenarios::revStartsAnywhereAndBreaksNoBlocks, 80));
        ALL.put("felling_still_works_after_revving", new Scenario(ChainsawScenarios::fellingStillWorksAfterRevving, 100));
    }

    private ChainsawScenarios() {}

    /** A fake player whose swing is fully charged and whose stats include the held tool (fake players never tick). */
    static final class Fighter extends FakePlayer {
        Fighter(ServerLevel level, String name, ItemStack tool) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
            setItemInHand(InteractionHand.MAIN_HAND, tool);
            getAttributes().addTransientAttributeModifiers(tool.getAttributeModifiers(EquipmentSlot.MAINHAND));
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

        /** Look straight ahead towards {@code yaw} (0 = south). */
        void face(float yaw) {
            setYRot(yaw);
            setYHeadRot(yaw);
            setXRot(0);
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
     * Pressing right-click starts the engine (rev-up, smoke) but nothing is sawn for the {@link ChainsawCombat#WINDUP}
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
        p.holdUse(ChainsawCombat.WINDUP);
        boolean quietDuringWindup = !hurt(golem) && tool.getDamageValue() == 0 && engine(p)[1] == 0;
        p.holdUse(1);
        float bite = golem.getMaxHealth() - golem.getHealth();
        LOG.info("[localchainsaws-test] wind-up: {} ticks without a bite: {}, then bite {} at tick {}, durability {}",
                ChainsawCombat.WINDUP, quietDuringWindup, bite, p.getTicksUsingItem() - 1, tool.getDamageValue());
        h.check(quietDuringWindup, "nothing should be sawn during the wind-up");
        h.check(bite > 0 && tool.getDamageValue() == 1 && engine(p)[1] == 1, "the first bite should land the tick the saw is revved up");
        p.releaseUsingItem();
        h.check(!p.isUsingItem() && engine(p)[2] == 1, "letting go should stop the saw and wind it down");

        float health = golem.getHealth();
        h.check(p.rev(), "right-click again should restart the engine");
        p.holdUse(ChainsawCombat.WINDUP);
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
        p.holdUse(ChainsawCombat.WINDUP + 1);
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

    /**
     * One sweep saws every mob in the cone within reach (three pigs ahead), and nothing behind, off to the side, out of
     * reach, behind a wall, the player's own wolf or an armor stand. Each pig sawn costs 1 durability.
     */
    static void revSawsEverythingInFront(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        Fighter p = fighter(h, "lcs-sweep", tool);
        p.face(0); // south, +z
        Pig ahead = mobAt(h, EntityType.PIG, p, 0, 2), left = mobAt(h, EntityType.PIG, p, 1.1, 2.3), right = mobAt(h, EntityType.PIG, p, -1.1, 2.3);
        Pig behind = mobAt(h, EntityType.PIG, p, 0, -2), side = mobAt(h, EntityType.PIG, p, 2, 0.6), far = mobAt(h, EntityType.PIG, p, 0, 4.6);
        Pig walled = mobAt(h, EntityType.PIG, p, -2.3, 0);
        for (int y = 0; y < 3; y++) h.setBlock(BASE.offset(-1, y + 1, 0), Blocks.STONE);
        Wolf wolf = mobAt(h, EntityType.WOLF, p, 0.6, 1.0);
        wolf.setTame(true);
        wolf.setOwnerUUID(p.getUUID());
        ArmorStand stand = EntityType.ARMOR_STAND.create(h.level());
        stand.moveTo(p.getX() - 0.6, p.getY(), p.getZ() + 1.0);
        h.level().addFreshEntity(stand);

        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawCombat.WINDUP + 1);
        List<Pig> sawn = List.of(ahead, left, right);
        boolean allSawn = sawn.stream().allMatch(ChainsawScenarios::hurt) && sawn.stream().allMatch(e -> effects(e)[1] == 1);
        boolean spared = !hurt(behind) && !hurt(side) && !hurt(far) && !hurt(walled) && !hurt(wolf) && stand.isAlive();
        int cost = tool.getDamageValue();
        LOG.info("[localchainsaws-test] sweep: ahead/left/right sawn {}, behind/side/far/walled/own wolf/armor stand spared {}, durability {}",
                allSawn, spared, cost);
        h.check(allSawn, "all three pigs in front should be sawn once");
        h.check(spared, "nothing behind, beside, out of reach, behind a wall, the own wolf or the armor stand should be hurt");
        h.check(cost == 3, "3 pigs sawn should cost 3 durability, cost " + cost);

        // Turn to the walled pig: the wall stops the chain; take the wall away and it bites.
        p.face(90); // west, -x
        p.holdUse(ChainsawCombat.REV_INTERVAL);
        h.check(!hurt(walled) && tool.getDamageValue() == 3, "the chain should not saw through a wall");
        for (int y = 0; y < 3; y++) h.setBlock(BASE.offset(-1, y + 1, 0), Blocks.AIR);
        p.holdUse(ChainsawCombat.REV_INTERVAL);
        h.check(hurt(walled) && tool.getDamageValue() == 4, "without the wall the pig should be sawn");
        p.releaseUsingItem();
        h.pass();
    }

    /** A worn chainsaw saws until it has 1 durability left, even mid-sweep, then stops by itself and won't start again. */
    static void revStopsAtOneDurability(TestArena h) {
        floor(h);
        ItemStack tool = new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 3);
        Fighter p = fighter(h, "lcs-rev-worn", tool);
        p.face(0);
        List<Pig> pigs = List.of(mobAt(h, EntityType.PIG, p, 0, 2), mobAt(h, EntityType.PIG, p, 1.1, 2.3), mobAt(h, EntityType.PIG, p, -1.1, 2.3));
        for (Pig pig : pigs) {
            pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            pig.setHealth(200);
        }
        h.check(p.rev(), "right-click should start the rev");
        p.holdUse(ChainsawCombat.WINDUP + 30);
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
        p.face(0);
        Pig far = mobAt(h, EntityType.PIG, p, 0, 5);
        h.check(p.rev(), "right-click in the air should start the engine");
        p.holdUse(ChainsawCombat.WINDUP + 20);
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
        p.holdUse(ChainsawCombat.WINDUP + 20);
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
        p.holdUse(ChainsawCombat.WINDUP + ChainsawCombat.REV_INTERVAL + 1);
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
        AABB box = new AABB(h.abs(BlockPos.ZERO), h.abs(new BlockPos(21, 40, 21))).inflate(2);
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
                    new AABB(h.abs(BlockPos.ZERO), h.abs(new BlockPos(21, 40, 21))).inflate(2))
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
        BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (int y = 1; y <= 24; y++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) h.setBlock(BASE.offset(dx, 0, dz).atY(y), Blocks.JUNGLE_LOG);
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
            BlockPos p = BASE.offset(dx, 0, dz).atY(25);
            h.setBlock(p, leaf);
            if (dx < 0 || dx > 1 || dz < 0 || dz > 1) h.setBlock(p.below(), leaf);
        }
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
}
