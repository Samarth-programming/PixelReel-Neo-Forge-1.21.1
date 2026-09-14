package com.pixelreel.poster;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.WallPosterBlockEntity;
import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.permissions.CinemaPermissions;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Assembles in-game poster uploads on the server and hangs them when the last chunk arrives. */
public final class CustomPosterUploads {
	private static final long SESSION_MILLIS = 60_000L;
	private static final int MAX_SESSIONS = 8;

	private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

	private CustomPosterUploads() {
	}

	public static void handleChunk(ModNetworkPayloads.UploadPosterChunk payload, ServerPlayer player) {
		prune();
		if (!CinemaPermissions.canPlacePosters(player)) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.no_permission"));
			return;
		}
		WallPosterBlockEntity poster = WallPosterBlock.posterAt(player.level(), payload.pos());
		if (poster == null) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.gone"));
			return;
		}
		if (payload.totalBytes() <= 0 || payload.totalBytes() > CustomPosterStore.MAX_BYTES) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_too_large"));
			return;
		}
		if (!CustomPosterStore.allowedName(payload.fileName()) || payload.data().length == 0) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_invalid"));
			return;
		}
		if (payload.offset() < 0 || payload.offset() + payload.data().length > payload.totalBytes()) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_invalid"));
			return;
		}
		String key = player.getUUID() + ":" + payload.uploadId();
		Session session = SESSIONS.computeIfAbsent(key, ignored -> {
			if (SESSIONS.size() >= MAX_SESSIONS) {
				return null;
			}
			return new Session(player.getUUID(), payload.pos(), payload.fileName(), payload.totalBytes());
		});
		if (session == null) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_invalid"));
			return;
		}
		synchronized (session) {
			System.arraycopy(payload.data(), 0, session.buffer, payload.offset(), payload.data().length);
			session.received += payload.data().length;
			if (session.received < session.buffer.length) {
				return;
			}
			SESSIONS.remove(key);
			try {
				String id = CustomPosterStore.save(session.fileName, session.buffer);
				poster.setArt(PosterArt.uploaded(id, CustomPosterStore.titleOf(session.fileName)));
				PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_ok"));
			} catch (IOException e) {
				PixelReel.LOGGER.warn("Could not save uploaded poster from {}: {}", player.getGameProfile().getName(), e.toString());
				PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.upload_invalid"));
			}
		}
	}

	public static void sendTo(ServerPlayer player, String id) {
		byte[] bytes = CustomPosterStore.load(id);
		if (bytes == null) {
			return;
		}
		int offset = 0;
		while (offset < bytes.length) {
			int length = Math.min(CustomPosterStore.CHUNK_BYTES, bytes.length - offset);
			byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + length);
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.CustomPosterChunk(id, bytes.length, offset, chunk));
			offset += length;
		}
	}

	private static void prune() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, Session>> iterator = SESSIONS.entrySet().iterator();
		while (iterator.hasNext()) {
			if (now - iterator.next().getValue().startedAt > SESSION_MILLIS) {
				iterator.remove();
			}
		}
	}

	private static final class Session {
		final UUID player;
		final BlockPos pos;
		final String fileName;
		final byte[] buffer;
		final long startedAt = System.currentTimeMillis();
		int received;

		Session(UUID player, BlockPos pos, String fileName, int totalBytes) {
			this.player = player;
			this.pos = pos;
			this.fileName = fileName;
			this.buffer = new byte[totalBytes];
		}
	}
}
