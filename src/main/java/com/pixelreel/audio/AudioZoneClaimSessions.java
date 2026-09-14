package com.pixelreel.audio;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.permissions.CinemaPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** Per-player 4-corner audio-zone claim wizard (A→B→C→D). */
public final class AudioZoneClaimSessions {
	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
	private static final char[] CORNER_LABELS = {'A', 'B', 'C', 'D'};

	private AudioZoneClaimSessions() {
	}

	public static InteractionResult handleUse(PlayerInteractEvent.RightClickBlock event) {
		if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (event.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		if (!serverPlayer.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		Session session = SESSIONS.get(serverPlayer.getUUID());
		if (session == null) {
			return InteractionResult.PASS;
		}
		if (!CinemaPermissions.canConfigureAudioZone(serverPlayer)) {
			cancel(serverPlayer, true);
			return InteractionResult.FAIL;
		}
		mark(serverPlayer, event.getPos());
		return InteractionResult.SUCCESS;
	}

	public static boolean isClaiming(ServerPlayer player) {
		return SESSIONS.containsKey(player.getUUID());
	}

	public static void start(ServerPlayer player, DisplayBlockEntity display) {
		SESSIONS.put(player.getUUID(), new Session(
			display.getBlockPos().immutable(),
			display.getLevel() == null ? Level.OVERWORLD : display.getLevel().dimension(),
			new ArrayList<>(4)
		));
		player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.started")
			.withStyle(ChatFormatting.AQUA));
		player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.hint_a")
			.withStyle(ChatFormatting.GRAY));
	}

	public static void cancel(ServerPlayer player, boolean notify) {
		Session removed = SESSIONS.remove(player.getUUID());
		if (removed != null && notify) {
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.cancelled")
				.withStyle(ChatFormatting.YELLOW));
		}
	}

	public static boolean mark(ServerPlayer player, BlockPos pos) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null) {
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.not_active")
				.withStyle(ChatFormatting.RED));
			return false;
		}
		if (!(player.level() instanceof ServerLevel level) || level.dimension() != session.dimension()) {
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.wrong_dimension")
				.withStyle(ChatFormatting.RED));
			return false;
		}
		DisplayBlockEntity display = resolveDisplay(level, session.displayPos());
		if (display == null) {
			SESSIONS.remove(player.getUUID());
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.display_gone")
				.withStyle(ChatFormatting.RED));
			return false;
		}

		BlockPos marked = pos.immutable();
		session.corners().add(marked);
		highlightCorner(level, marked);
		int index = session.corners().size();
		char label = CORNER_LABELS[index - 1];

		if (index == 1) {
			player.sendSystemMessage(Component.translatable(
				"command.pixelreel.zone.claim.corner_a",
				marked.getX(), marked.getY(), marked.getZ()
			).withStyle(ChatFormatting.GREEN));
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.hint_b")
				.withStyle(ChatFormatting.GRAY));
		} else if (index == 2) {
			int height = Math.abs(marked.getY() - session.corners().get(0).getY()) + 1;
			player.sendSystemMessage(Component.translatable(
				"command.pixelreel.zone.claim.corner_b",
				marked.getX(), marked.getY(), marked.getZ(), height
			).withStyle(ChatFormatting.GREEN));
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.hint_c")
				.withStyle(ChatFormatting.GRAY));
		} else if (index == 3) {
			player.sendSystemMessage(Component.translatable(
				"command.pixelreel.zone.claim.corner_c",
				marked.getX(), marked.getY(), marked.getZ()
			).withStyle(ChatFormatting.GREEN));
			player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.hint_d")
				.withStyle(ChatFormatting.GRAY));
		} else {
			AudioZone zone = AudioZone.fromCorners(session.corners()).expanded(1);
			display.setAudioZone(zone);
			outlineZone(level, zone);
			SESSIONS.remove(player.getUUID());
			player.sendSystemMessage(Component.translatable(
				"command.pixelreel.zone.claim.complete",
				zone.sizeLabel(),
				zone.describe()
			).withStyle(ChatFormatting.GOLD));
			return true;
		}

		player.sendSystemMessage(Component.translatable("command.pixelreel.zone.claim.progress", String.valueOf(label), index, 4)
			.withStyle(ChatFormatting.DARK_GRAY));
		return true;
	}

	private static @Nullable DisplayBlockEntity resolveDisplay(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof DisplayBlockEntity display) {
			return display;
		}
		return DisplayBlock.controllerAt(level, pos);
	}

	public static void outlineZone(@Nullable ServerLevel level, @Nullable AudioZone zone) {
		if (level == null || zone == null) {
			return;
		}
		BlockPos min = zone.min();
		BlockPos max = zone.max();
		double y0 = min.getY() + 0.15;
		double y1 = max.getY() + 0.85;
		double[] xs = {min.getX() + 0.5, max.getX() + 0.5};
		double[] zs = {min.getZ() + 0.5, max.getZ() + 0.5};
		for (double x : xs) {
			for (double z : zs) {
				level.sendParticles(ParticleTypes.END_ROD, x, y0, z, 2, 0.0, 0.0, 0.0, 0.0);
				level.sendParticles(ParticleTypes.END_ROD, x, y1, z, 2, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private static void highlightCorner(ServerLevel level, BlockPos pos) {
		level.sendParticles(
			ParticleTypes.HAPPY_VILLAGER,
			pos.getX() + 0.5,
			pos.getY() + 0.5,
			pos.getZ() + 0.5,
			12,
			0.25,
			0.25,
			0.25,
			0.0
		);
	}

	private record Session(BlockPos displayPos, ResourceKey<Level> dimension, List<BlockPos> corners) {
	}
}
