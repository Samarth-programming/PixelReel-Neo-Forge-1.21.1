package com.pixelreel.permissions;

import com.pixelreel.PixelReel;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Optional bridge to a permission manager such as LuckPerms, so an admin can grant a pixelReel permission
 * without handing out operator status.
 *
 * <p>Bound reflectively so servers without a permission manager keep working unchanged.
 */
public final class PermissionNodes {
	public static final String WATCH = "pixelreel.watch";
	public static final String BROWSE = "pixelreel.browse";
	public static final String PLAY_TUNARR = "pixelreel.play.tunarr";
	public static final String PLAY_MOVIES = "pixelreel.play.movies";
	public static final String PLAY_SHOWS = "pixelreel.play.shows";
	public static final String CONTROL = "pixelreel.control";
	public static final String POSTERS = "pixelreel.posters";
	public static final String POSTER_URLS = "pixelreel.posters.url";
	public static final String CONFIGURE_TUNARR = "pixelreel.configure.tunarr";
	public static final String CONFIGURE_JELLYFIN = "pixelreel.configure.jellyfin";
	public static final String CONFIGURE_EMBY = "pixelreel.configure.emby";
	public static final String CONFIGURE_PLEX = "pixelreel.configure.plex";
	public static final String REFRESH_LIBRARY = "pixelreel.library.refresh";
	public static final String AUDIO_ZONE = "pixelreel.audiozone";
	public static final String SEE_HOSTS = "pixelreel.hosts";

	private static boolean resolved;
	private static @Nullable BiFunction<ServerPlayer, String, Optional<Boolean>> lookup;

	private PermissionNodes() {
	}

	/**
	 * The explicitly configured value for a node, or empty when no permission manager is installed or the
	 * node has been left unset. An unset node falls through to the config rule rather than denying.
	 */
	public static Optional<Boolean> value(ServerPlayer player, String node) {
		BiFunction<ServerPlayer, String, Optional<Boolean>> check = method();
		if (check == null) {
			return Optional.empty();
		}
		try {
			return check.apply(player, node);
		} catch (RuntimeException e) {
			PixelReel.LOGGER.debug("Permission node lookup failed for {}: {}", node, e.toString());
			return Optional.empty();
		}
	}

	public static boolean isAvailable() {
		return method() != null;
	}

	private static synchronized @Nullable BiFunction<ServerPlayer, String, Optional<Boolean>> method() {
		if (resolved) {
			return lookup;
		}
		resolved = true;
		lookup = fabricPermissionsApi();
		if (lookup != null) {
			PixelReel.LOGGER.info("pixelReel permission nodes are available (fabric-permissions-api detected)");
			return lookup;
		}
		lookup = luckPermsApi();
		if (lookup != null) {
			PixelReel.LOGGER.info("pixelReel permission nodes are available (LuckPerms API detected)");
			return lookup;
		}
		return null;
	}

	private static @Nullable BiFunction<ServerPlayer, String, Optional<Boolean>> fabricPermissionsApi() {
		try {
			Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
			Method check = permissions.getMethod("check", Entity.class, String.class, boolean.class);
			return (player, node) -> {
				try {
					boolean whenDefaultTrue = (boolean) check.invoke(null, player, node, true);
					boolean whenDefaultFalse = (boolean) check.invoke(null, player, node, false);
					return whenDefaultTrue == whenDefaultFalse ? Optional.of(whenDefaultTrue) : Optional.empty();
				} catch (ReflectiveOperationException | ClassCastException e) {
					throw new RuntimeException(e);
				}
			};
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			return null;
		}
	}

	private static @Nullable BiFunction<ServerPlayer, String, Optional<Boolean>> luckPermsApi() {
		try {
			Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
			Object api = provider.getMethod("get").invoke(null);
			Method getUserManager = api.getClass().getMethod("getUserManager");
			Object userManager = getUserManager.invoke(api);
			Method getUser = userManager.getClass().getMethod("getUser", UUID.class);
			return (player, node) -> {
				try {
					Object user = getUser.invoke(userManager, player.getUUID());
					if (user == null) {
						return Optional.empty();
					}
					Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
					Object permData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
					Object tristate = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);
					String name = String.valueOf(tristate);
					if ("TRUE".equalsIgnoreCase(name)) {
						return Optional.of(true);
					}
					if ("FALSE".equalsIgnoreCase(name)) {
						return Optional.of(false);
					}
					return Optional.empty();
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			};
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
