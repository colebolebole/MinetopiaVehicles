package nl.mtvehicles.core.infrastructure.vehicle;

import nl.mtvehicles.core.Main;
import nl.mtvehicles.core.infrastructure.dataconfig.DefaultConfig;
import nl.mtvehicles.core.infrastructure.enums.VehicleType;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.utils.TextUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows the vehicles' license plates as text on the vehicles (where the license plate would be).
 * <p>
 * Every vehicle that should have a plate gets an invisible, non-persistent armor stand with a visible custom name
 * (the formatted license plate) which follows the vehicle's skin armor stand, rotated by its yaw.
 * Works on all supported server versions (no Display entities needed).
 * <p>
 * Settings are in config.yml ('licensePlate'); a vehicle model in vehicles.yml may override them with its own
 * 'licensePlate' section (or disable the plate using 'licensePlate: false').
 *
 * @since 2.5.9
 */
public final class LicensePlateManager {
    private LicensePlateManager() {}

    private static final String SKIN_PREFIX = "MTVEHICLES_SKIN_";
    /**
     * Scoreboard tag of plate armor stands (so leftovers, e.g. after a /reload, can be cleaned up).
     */
    private static final String PLATE_TAG = "MTVEHICLES_PLATE";
    /**
     * A name tag is rendered about this far above a marker armor stand, so the stand is placed this much lower.
     */
    private static final double NAME_TAG_HEIGHT = 0.5;
    private static final long SCAN_INTERVAL_TICKS = 40L;

    private static final Map<String, Entry> entries = new HashMap<>();
    private static BukkitTask tickTask;
    private static BukkitTask scanTask;

    private static final class Entry {
        final String license;
        ArmorStand skin;
        ArmorStand plate;
        boolean enabled;
        double x, y, z;
        String text;

        Entry(String license, ArmorStand skin) {
            this.license = license;
            this.skin = skin;
        }
    }

    /**
     * Start showing license plates (called when the plugin is enabled).
     */
    public static void start() {
        stop();
        removeAllPlateStands();
        tickTask = Bukkit.getScheduler().runTaskTimer(Main.instance, LicensePlateManager::tick, 1L, 1L);
        scanTask = Bukkit.getScheduler().runTaskTimer(Main.instance, LicensePlateManager::scan, 20L, SCAN_INTERVAL_TICKS);
    }

    /**
     * Stop showing license plates and remove all plate armor stands (called when the plugin is disabled).
     */
    public static void stop() {
        if (tickTask != null) tickTask.cancel();
        if (scanTask != null) scanTask.cancel();
        tickTask = null;
        scanTask = null;
        for (Entry entry : entries.values()) removePlate(entry);
        entries.clear();
    }

    /**
     * Re-read the settings (e.g. after the configs have been reloaded). Plates are re-created within a couple of seconds.
     */
    public static void refresh() {
        if (tickTask == null) return; // not running
        for (Entry entry : entries.values()) removePlate(entry);
        entries.clear();
    }

    /**
     * Remove all plate armor stands which are not tracked (they might be left over after a reload or a crash).
     */
    private static void removeAllPlateStands() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isPlateStand(entity)) entity.remove();
            }
        }
    }

    private static boolean isPlateStand(Entity entity) {
        return entity instanceof ArmorStand && entity.getScoreboardTags().contains(PLATE_TAG);
    }

    // --- Settings ---

    private static boolean isEnabled() {
        Object value = ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_ENABLED);
        return !(value instanceof Boolean) || (Boolean) value;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    /**
     * Resolve the (cached) settings of a vehicle - which is relatively expensive, so this is done only once per vehicle.
     */
    private static void loadSettings(Entry entry) {
        entry.enabled = false;
        try {
            final Object typesValue = ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_VEHICLE_TYPES);
            final VehicleType type = ConfigModule.vehicleDataConfig.getType(entry.license);
            boolean enabled = false;
            if (typesValue instanceof List) {
                for (Object t : (List<?>) typesValue) {
                    if (t != null && t.toString().equalsIgnoreCase(type.name())) enabled = true;
                }
            }

            double x = number(ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_X), -1.2);
            double y = number(ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_Y), 0.45);
            double z = number(ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_Z), 0.0);

            // Per-model overrides from vehicles.yml
            final Vehicle vehicle = VehicleUtils.getVehicle(entry.license);
            final Map<?, ?> model = vehicle != null ? vehicle.getVehicleData() : null;
            final Object override = model != null ? model.get("licensePlate") : null;
            if (override instanceof Boolean) {
                enabled = (Boolean) override;
            } else if (override instanceof Map) {
                final Map<?, ?> section = (Map<?, ?>) override;
                if (section.get("enabled") instanceof Boolean) enabled = (Boolean) section.get("enabled");
                else enabled = true; // a section with offsets means that this model wants a plate
                x = number(section.get("x"), x);
                y = number(section.get("y"), y);
                z = number(section.get("z"), z);
            }

            Object format = ConfigModule.defaultConfig.get(DefaultConfig.Option.LICENSE_PLATE_FORMAT);
            String formatString = format != null ? format.toString() : "&f&l%plate%";

            entry.enabled = enabled;
            entry.x = x;
            entry.y = y;
            entry.z = z;
            entry.text = TextUtils.colorize(formatString.replace("%plate%", entry.license));
        } catch (Exception e) {
            // Malformed vehicle data - simply don't show a plate (other parts of the plugin report such problems).
            entry.enabled = false;
        }
    }

    // --- Tasks ---

    /**
     * Find vehicles which don't have a plate yet. Runs every couple of seconds.
     */
    private static void scan() {
        if (!isEnabled()) {
            if (!entries.isEmpty()) {
                for (Entry entry : entries.values()) removePlate(entry);
                entries.clear();
            }
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand)) continue;
                final String name = entity.getCustomName();
                if (name == null || !name.startsWith(SKIN_PREFIX)) continue;

                final String license = VehicleUtils.getLicensePlate(entity);
                if (license == null || !VehicleUtils.existsByLicensePlate(license)) continue;

                Entry entry = entries.get(license);
                if (entry == null) {
                    entry = new Entry(license, (ArmorStand) entity);
                    loadSettings(entry);
                    entries.put(license, entry);
                } else if (entry.skin == null || !entry.skin.isValid()) {
                    entry.skin = (ArmorStand) entity;
                }
            }
        }

        // Remove plate stands which don't belong to any tracked vehicle
        final Set<UUID> tracked = new HashSet<>();
        for (Entry entry : entries.values()) {
            if (entry.plate != null) tracked.add(entry.plate.getUniqueId());
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isPlateStand(entity) && !tracked.contains(entity.getUniqueId())) entity.remove();
            }
        }
    }

    /**
     * Move the plates along with their vehicles. Runs every tick (only goes through the tracked vehicles, which is cheap).
     */
    private static void tick() {
        final Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            final Entry entry = iterator.next();

            // While a vehicle is being driven, its skin armor stand is a different one (see VehicleUtils#enterVehicle)
            ArmorStand skin = VehicleData.autostand.get(SKIN_PREFIX + entry.license);
            if (skin == null || !skin.isValid()) skin = entry.skin;
            if (skin == null || !skin.isValid()) {
                // Vehicle picked up/despawned or its chunk has been unloaded
                removePlate(entry);
                iterator.remove();
                continue;
            }
            entry.skin = skin;

            if (!entry.enabled) continue;

            final Location target = getPlateLocation(skin.getLocation(), entry);
            if (entry.plate == null || !entry.plate.isValid()) {
                entry.plate = spawnPlate(target, entry.text);
                continue;
            }

            final Location current = entry.plate.getLocation();
            if (current.getWorld() != target.getWorld() || current.distanceSquared(target) > 1.0E-4) {
                entry.plate.teleport(target);
            }
        }
    }

    /**
     * Get the location of the plate armor stand. The offsets use the same convention as the seats in vehicles.yml:
     * x = forwards (+) / backwards (-), y = up, z = sideways.
     */
    private static Location getPlateLocation(Location skinLocation, Entry entry) {
        final double yaw = Math.toRadians(skinLocation.getYaw());
        final double forwardX = -Math.sin(yaw);
        final double forwardZ = Math.cos(yaw);
        final double x = skinLocation.getX() + forwardX * entry.x + entry.z * Math.cos(yaw);
        final double z = skinLocation.getZ() + forwardZ * entry.x + entry.z * Math.sin(yaw);
        return new Location(skinLocation.getWorld(), x, skinLocation.getY() + entry.y - NAME_TAG_HEIGHT, z, skinLocation.getYaw(), 0f);
    }

    private static ArmorStand spawnPlate(Location location, String text) {
        final ArmorStand plate = location.getWorld().spawn(location, ArmorStand.class);
        plate.setVisible(false);
        plate.setGravity(false);
        plate.setSmall(true);
        plate.setMarker(true); // no hitbox - cannot be clicked, punched or pushed
        plate.setCustomName(text);
        plate.setCustomNameVisible(true);
        plate.addScoreboardTag(PLATE_TAG);
        try {
            plate.setPersistent(false); // never saved with the chunk - re-created when the vehicle loads
        } catch (NoSuchMethodError ignored) {
            // Older server versions - leftovers are cleaned up by removeAllPlateStands() instead
        }
        try {
            plate.setInvulnerable(true);
        } catch (NoSuchMethodError ignored) {
        }
        return plate;
    }

    private static void removePlate(Entry entry) {
        if (entry.plate != null) {
            entry.plate.remove();
            entry.plate = null;
        }
    }
}
