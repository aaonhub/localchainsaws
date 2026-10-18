package net.bdoyl.localchainsaws;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Breaking a log of a natural tree with a chainsaw (not sneaking) fells the whole tree: every connected log of the same
 * kind above the cut comes down through its normal loot table with the chainsaw as the tool (so Fortune / Silk Touch /
 * loot modifiers apply), the leaves that would now decay drop what decay drops (saplings, sticks, apples), everything
 * lands at the stump, and a sapling is replanted when one dropped.
 *
 * Sneaking is left alone on purpose: on The Local, Tree Harvester (server-only) fells trees for any axe while sneaking,
 * so the two never both run on one break. Without Tree Harvester, sneaking just chops the single log.
 */
public final class TreeFeller {
    /** How far sideways from the cut log a tree may reach (acacia/jungle branches, 2x2 trunks). */
    static final int MAX_RADIUS = 12;
    /** Vanilla leaves survive within 6 steps (through leaves) of a log. */
    static final int LEAF_REACH = 6;
    /** Natural (not player-placed) leaves that must touch the logs before anything is felled. */
    static final int MIN_NATURAL_LEAVES = 4;
    /** How many logs we look at to decide "is this a natural tree" (more than any chainsaw cuts, so big trees still count). */
    static final int SCAN_LOGS = 1024;

    private static final Direction[] DIRS = Direction.values();
    private static boolean felling; // server thread only: our own per-log BreakEvents must not start another felling
    private static final List<Runnable> NEXT_TICK = new ArrayList<>();

    private TreeFeller() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (felling) return;
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown() || player.getAbilities().instabuild) return;
        ItemStack tool = player.getMainHandItem();
        if (!(tool.getItem() instanceof ChainsawItem saw)) return;
        BlockState state = event.getState();
        if (!state.is(BlockTags.LOGS)) return;

        BlockPos origin = event.getPos().immutable();
        List<BlockPos> tree = findLogs(level, origin, state.getBlock(), SCAN_LOGS);
        if (tree.size() < 2 || !hasNaturalLeaves(level, tree)) return; // a lone log or something player-built: just the one block
        felling = true;
        try {
            // Nearest first, so a tree bigger than the cap is cut from the bottom up and the rest stays standing.
            fell(level, player, tool, origin, tree.subList(0, Math.min(tree.size(), saw.maxLogs())));
        } finally {
            felling = false;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || NEXT_TICK.isEmpty()) return;
        List<Runnable> tasks = new ArrayList<>(NEXT_TICK);
        NEXT_TICK.clear();
        tasks.forEach(Runnable::run);
    }

    /** Connected logs of the same block, nearest first, never below the cut, at most {@code max} (the cut log included). */
    static List<BlockPos> findLogs(ServerLevel level, BlockPos origin, Block block, int max) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && found.size() < max) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (next.getY() < origin.getY() || Math.abs(next.getX() - origin.getX()) > MAX_RADIUS
                                || Math.abs(next.getZ() - origin.getZ()) > MAX_RADIUS || !seen.add(next)) continue;
                        if (level.getBlockState(next).is(block)) queue.add(next);
                    }
                }
            }
        }
        return found;
    }

    static boolean isNaturalLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
    }

    static boolean hasNaturalLeaves(ServerLevel level, List<BlockPos> logs) {
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (Direction d : DIRS) {
                BlockPos n = log.relative(d);
                if (isNaturalLeaf(level.getBlockState(n)) && leaves.add(n) && leaves.size() >= MIN_NATURAL_LEAVES) return true;
            }
        }
        return false;
    }

    private static void fell(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos origin, List<BlockPos> logs) {
        Set<BlockPos> felled = new HashSet<>();
        felled.add(origin); // the game breaks this one itself right after this event (and takes its 1 durability)
        List<ItemStack> drops = new ArrayList<>();
        for (BlockPos pos : logs) {
            if (pos.equals(origin)) continue;
            // Keep 1 durability for the cut log and 1 more so the chainsaw never breaks mid-tree.
            if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 2) break;
            BlockState st = level.getBlockState(pos);
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, st, player))) continue; // claims/protection said no
            BlockEntity be = level.getBlockEntity(pos);
            drops.addAll(Block.getDrops(st, level, pos, be, player, tool));
            level.removeBlock(pos, false);
            level.levelEvent(2001, pos, Block.getId(st));
            tool.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
            player.awardStat(Stats.BLOCK_MINED.get(st.getBlock()));
            player.causeFoodExhaustion(0.005F);
            felled.add(pos);
        }

        int i = 0;
        for (BlockPos pos : doomedLeaves(level, felled, Math.max(256, logs.size() * 12))) {
            BlockState st = level.getBlockState(pos);
            drops.addAll(Block.getDrops(st, level, pos, null)); // what decay would drop: saplings, sticks, apples
            level.removeBlock(pos, false);
            if ((i++ & 3) == 0) level.levelEvent(2001, pos, Block.getId(st));
        }

        replant(level, origin, felled, drops);
        for (ItemStack stack : merge(drops)) Block.popResource(level, origin, stack);
        level.playSound(null, origin, LocalChainsaws.CHAINSAW_CUT.get(), SoundSource.PLAYERS, 1.0F,
                0.9F + level.random.nextFloat() * 0.2F);
    }

    /**
     * Natural leaves within reach of the felled logs that no remaining log keeps alive any more (vanilla's distance rule:
     * a leaf lives while a log is at most 6 steps away through leaves). Neighbouring trees keep their own leaves.
     */
    static List<BlockPos> doomedLeaves(ServerLevel level, Set<BlockPos> felled, int cap) {
        Map<BlockPos, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos log : felled) {
            for (Direction d : DIRS) {
                BlockPos n = log.relative(d);
                if (!depth.containsKey(n) && isNaturalLeaf(level.getBlockState(n))) {
                    depth.put(n, 1);
                    queue.add(n);
                }
            }
        }
        while (!queue.isEmpty() && depth.size() < cap) {
            BlockPos pos = queue.poll();
            int d = depth.get(pos);
            if (d >= LEAF_REACH) continue;
            for (Direction dir : DIRS) {
                BlockPos n = pos.relative(dir);
                if (!depth.containsKey(n) && isNaturalLeaf(level.getBlockState(n))) {
                    depth.put(n, d + 1);
                    queue.add(n);
                }
            }
        }

        // Shortest leaf-distance from each candidate to a log that stays (or to a leaf outside the candidates that is
        // itself alive), level by level.
        Map<BlockPos, Integer> support = new HashMap<>();
        List<List<BlockPos>> buckets = new ArrayList<>();
        for (int b = 0; b <= LEAF_REACH; b++) buckets.add(new ArrayList<>());
        for (BlockPos pos : depth.keySet()) {
            int best = Integer.MAX_VALUE;
            for (Direction dir : DIRS) {
                BlockPos n = pos.relative(dir);
                BlockState ns = level.getBlockState(n);
                if (ns.is(BlockTags.LOGS)) {
                    if (!felled.contains(n)) best = 1;
                } else if (!depth.containsKey(n) && ns.hasProperty(LeavesBlock.DISTANCE)) {
                    best = Math.min(best, ns.getValue(LeavesBlock.DISTANCE) + 1);
                }
            }
            if (best <= LEAF_REACH) {
                support.put(pos, best);
                buckets.get(best).add(pos);
            }
        }
        for (int d = 1; d < LEAF_REACH; d++) {
            for (BlockPos pos : buckets.get(d)) {
                if (support.get(pos) != d) continue;
                for (Direction dir : DIRS) {
                    BlockPos n = pos.relative(dir);
                    if (depth.containsKey(n) && support.getOrDefault(n, Integer.MAX_VALUE) > d + 1) {
                        support.put(n, d + 1);
                        buckets.get(d + 1).add(n);
                    }
                }
            }
        }

        List<BlockPos> doomed = new ArrayList<>();
        for (BlockPos pos : depth.keySet()) if (!support.containsKey(pos)) doomed.add(pos);
        doomed.sort((a, b) -> Integer.compare(depth.get(a), depth.get(b)));
        return doomed;
    }

    /**
     * Plants a sapling from the drops on the stump once the game has removed the cut log; for a 2x2 trunk (dark oak,
     * giant spruce/jungle) all four spots get one when four of the same sapling dropped, so it can grow back.
     */
    private static void replant(ServerLevel level, BlockPos origin, Set<BlockPos> felled, List<ItemStack> drops) {
        if (!level.getBlockState(origin.below()).is(BlockTags.DIRT)) return; // only when the tree was cut at the base
        for (ItemStack stack : drops) {
            if (!stack.is(ItemTags.SAPLINGS) || !(stack.getItem() instanceof BlockItem item)) continue;
            int have = 0;
            for (ItemStack s : drops) if (ItemStack.isSameItemSameTags(s, stack)) have += s.getCount();
            List<BlockPos> spots = List.of(origin);
            if (have >= 4) {
                List<BlockPos> square = square2x2(origin, felled);
                if (square != null) spots = square;
            }
            int need = spots.size();
            for (ItemStack s : drops) {
                if (need == 0) break;
                if (ItemStack.isSameItemSameTags(s, stack)) need -= s.split(Math.min(need, s.getCount())).getCount();
            }
            BlockState sapling = item.getBlock().defaultBlockState();
            List<BlockPos> plant = spots;
            NEXT_TICK.add(() -> {
                for (BlockPos pos : plant) {
                    if (level.getBlockState(pos).isAir() && sapling.canSurvive(level, pos)) level.setBlock(pos, sapling, Block.UPDATE_ALL);
                    else Block.popResource(level, origin, new ItemStack(item));
                }
            });
            return;
        }
    }

    /** The 2x2 base of a big trunk that includes the cut log, or null. */
    static List<BlockPos> square2x2(BlockPos origin, Set<BlockPos> felled) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                BlockPos c = origin.offset(dx, 0, dz);
                List<BlockPos> sq = List.of(c, c.east(), c.south(), c.east().south());
                if (felled.containsAll(sq)) return sq;
            }
        }
        return null;
    }

    /** Fewer, fuller stacks instead of hundreds of item entities. */
    static List<ItemStack> merge(List<ItemStack> drops) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) continue;
            ItemStack rest = stack.copy();
            for (ItemStack have : out) {
                if (rest.isEmpty()) break;
                if (ItemStack.isSameItemSameTags(have, rest) && have.getCount() < have.getMaxStackSize()) {
                    int move = Math.min(rest.getCount(), have.getMaxStackSize() - have.getCount());
                    have.grow(move);
                    rest.shrink(move);
                }
            }
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }
}
