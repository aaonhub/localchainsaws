package net.bdoyl.localchainsaws;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Tells a player when the chainsaw in their hand is wearing out: an action-bar line and the engine sputtering (only they
 * hear it) when it drops to {@link ChainsawConfig#lowDurabilityPercent} of its durability, and again when it is down to
 * its last point, where felling and revving stop (a normal block break would still break it for good).
 *
 * Watches the main hand once a tick and only speaks when the same chainsaw goes down past a mark, so picking up or
 * switching to an already worn one stays quiet.
 */
public final class ChainsawWear {
    /** The chainsaw last seen in a player's hand and how much it had left then. */
    private static final class Seen {
        ItemStack stack;
        int left;
    }

    private static final Map<ServerPlayer, Seen> SEEN = new WeakHashMap<>(); // server thread only

    /** Warnings per player id, only kept while the test scenarios run: {low, worn out}. */
    static boolean record;
    static final Map<Integer, int[]> WARNINGS = new HashMap<>();

    private ChainsawWear() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) Guard.run("wear check", () -> check(player));
    }

    /** Durability at or below which the "wearing out" warning comes, 0 = off. */
    static int lowMark(ItemStack stack) {
        int percent = ChainsawConfig.lowDurabilityPercent();
        return percent <= 0 ? 0 : Math.max(2, (int) Math.ceil(stack.getMaxDamage() * percent / 100.0));
    }

    static void check(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ChainsawItem) || !stack.isDamageableItem()) {
            SEEN.remove(player);
            return;
        }
        int left = stack.getMaxDamage() - stack.getDamageValue();
        Seen seen = SEEN.computeIfAbsent(player, p -> new Seen());
        boolean same = seen.stack == stack;
        int before = seen.left;
        seen.stack = stack;
        seen.left = left;
        if (!same || left >= before) return;
        if (left <= 1) {
            warn(player, Component.translatable("localchainsaws.wear.out").withStyle(ChatFormatting.RED), 0.5F, 1);
        } else {
            int mark = lowMark(stack);
            if (left <= mark && before > mark) {
                warn(player, Component.translatable("localchainsaws.wear.low", left).withStyle(ChatFormatting.GOLD), 0.7F, 0);
            }
        }
    }

    private static void warn(ServerPlayer player, Component message, float pitch, int kind) {
        player.displayClientMessage(message, true);
        player.playNotifySound(LocalChainsaws.CHAINSAW_REV_DOWN.get(), SoundSource.PLAYERS, 0.8F, pitch);
        if (record) WARNINGS.computeIfAbsent(player.getId(), id -> new int[2])[kind]++;
    }
}
