package com.norcode.bukkit.buildinabox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraft.server.v1_5_R3.Packet61WorldEvent;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.craftbukkit.v1_5_R3.entity.CraftPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.material.EnderChest;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.CuboidClipboard;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.blocks.TileEntityBlock;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.data.DataException;
import com.sk89q.worldedit.schematic.SchematicFormat;

public class BuildChest {
    final static long PREVIEW_DURATION = 20*4; //TODO: Move Me.
    BuildInABox plugin;
    private boolean previewing = false;
    private BuildingPlan plan;
    private LockingTask lockingTask = null;
    private BukkitTask buildTask = null;
    private ChestData data;

    public BuildChest(ChestData data) {
        this.plugin = BuildInABox.getInstance();
        this.data = data;
        this.plan = BuildInABox.getInstance().getDataStore().getBuildingPlan(data.getPlanName());
    }

    public int getId() {
        return data.getId();
    }

    public boolean isLocking() {
        return lockingTask != null;
    }

    LockingTask getLockingTask() {
        return lockingTask;
    }

    public boolean isLocked() {
        return data.getLockedBy() != null;
    }

    public String getLockedBy() {
        return data.getLockedBy();
    }

    public Location getLocation() {
        return data.getLocation();
    }

    public BuildingPlan getPlan() {
        return plan;
    }

    
    public void endPreview(final Player player) {
        Block b = getBlock();
        if (b != null && previewing && b.getType().equals(Material.ENDER_CHEST)) {
            plan.clearPreview(player.getName(), b);
            b.setType(Material.AIR);
            data.setLocation(null);
            plugin.getDataStore().saveChest(data);
            b.getWorld().dropItem(new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5), data.toItemStack());
            previewing = false;
        }
    }

    public boolean isPreviewing() {
        return previewing;
    }

    public EnderChest getEnderChest() {
        return (EnderChest) getBlock().getState().getData();
    }

    public Block getBlock() {
        if (data.getLocation() != null) {
            return data.getLocation().getBlock();
        }
        return null;
    }

    public void preview(final Player player) {
        previewing = true;
        
        if (plan.sendPreview(player, getBlock())) {
            player.sendMessage(getDescription());
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                public void run() {
                    endPreview(player);
                }
            }, PREVIEW_DURATION);
        } else {
            endPreview(player);
            player.sendMessage(ChatColor.GOLD + getPlan().getName() + " won't fit here.");
        }
    }

    public void protectBlocks() {
        plan.protectBlocks(getBlock(), null);
    }

    public void build(Player player) {
        if (previewing && getBlock().getType().equals(Material.ENDER_CHEST)) {
            plan.clearPreview(player.getName(), getBlock());
            previewing = false;
        }
        final World world = player.getWorld();
        final int blocksPerTick = 4;
        data.setLocation(getLocation());
        data.setLastActivity(System.currentTimeMillis());
        plugin.getDataStore().saveChest(data);
        final List<Player> nearby = new ArrayList<Player>();
        this.previewing = false;
        for (Player p: world.getPlayers()) {
            if (p.getLocation().distance(getLocation()) < 16) {
                nearby.add(p);
            }
        }
        CuboidClipboard clipboard = null;
        try {
            clipboard = SchematicFormat.MCEDIT.load(plan.getSchematicFile());
            
            if (data.getTileEntities() != null) {
                CompoundTag tag;
                BuildInABox.getInstance().debug("TileEntities: " + data.getTileEntities()); 
                for (Entry<BlockVector, CompoundTag> entry: data.getTileEntities().entrySet()) {
                    tag = entry.getValue();
                    Map<String, Tag> values = new HashMap<String, Tag>();
                    for (Entry<String, Tag> tagEntry: tag.getValue().entrySet()) {
                        if (tagEntry.getKey().equals("x")) {
                            values.put("x", new IntTag("x", entry.getKey().getBlockX()));
                        } else if (tagEntry.getKey().equals("y")) {
                            values.put("y", new IntTag("y", entry.getKey().getBlockY()));
                        } else if (tagEntry.getKey().equals("z")) {
                            values.put("z", new IntTag("z", entry.getKey().getBlockZ()));
                        } else {
                            values.put(tagEntry.getKey(), tagEntry.getValue());
                        }
                    }
                    BuildInABox.getInstance().debug("Setting clipboard nbt data:" + entry.getKey() + " -> " + values);
                    clipboard.getPoint(entry.getKey()).setNbtData(new CompoundTag("", values));
                }
            }
            clipboard.rotate2D(BuildingPlan.getRotationDegrees(BlockFace.NORTH, getEnderChest().getFacing()));
        } catch (IOException e) {
            e.printStackTrace();
        } catch (DataException e) {
            e.printStackTrace();
        }
        buildTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new BuildingTask(this, clipboard) {
            @Override
            public void run() {
                BaseBlock bb;
                for (int i=0;i<blocksPerTick;i++) {
                    if (moveCursor()) {
                        BuildInABox.getInstance().debug("Building @ " + cursor + " ==> " + worldCursor);
                        bb = clipboard.getPoint(cursor);
                        if (bb.getType() == 0) continue; // skip air blocks;
                        if (cursor.getBlockY() < -clipboard.getOffset().getBlockY()) {
                            // store replaced Block
                            if (worldCursor.getBlock().getTypeId() != 0) {
                                replacedBlocks.put(new BlockVector(cursor), new BaseBlock(worldCursor.getBlock().getTypeId(), worldCursor.getBlock().getData()));
                            }
                        }
                        Packet61WorldEvent packet = new Packet61WorldEvent(2001, worldCursor.getBlockX(), worldCursor.getBlockY(), worldCursor.getBlockZ(), bb.getType(), false);
                        for (Player p: nearby) {
                            if (p.isOnline()) {
                                ((CraftPlayer) p).getHandle().playerConnection.sendPacket(packet);
                                p.sendBlockChange(worldCursor, bb.getType(), (byte) bb.getData());
                            }
                        }
                    } else {
                        BuildInABox.getInstance().debug("finished building...");
                        // clipboard paste, save data etc.
                        plan.build(getBlock(), clipboard);
                        data.setReplacedBlocks(replacedBlocks);
                        data.clearTileEntities();
                        buildTask.cancel();
                        plugin.getDataStore().saveChest(data);
                        return;
                    }
                }
            }
        }, 1, 1);
        
    }


    public void unlock(Player player) {
        long total = plugin.getConfig().getLong("unlock-time", 10);
        if (data.getLockedBy().equals(player.getName())) {
            total = plugin.getConfig().getLong("unlock-time-own", 5);
        }
        lockingTask = new UnlockingTask(player.getName(), total);
        lockingTask.run();
    }

    public void lock(Player player) {
        long total = plugin.getConfig().getLong("lock-time", 10);
        lockingTask = new LockingTask(player.getName(), total);
        lockingTask.run();
    }

    public void pickup(Player player) {
        final int blocksPerTick = 10;
        List<Player> nearby = new ArrayList<Player>();
        for (Player p: player.getWorld().getPlayers()) {
            nearby.add(p);
        }
        final BukkitWorld bukkitWorld = new BukkitWorld(player.getWorld());
        if (!isLocked()) {
            data.clearTileEntities();
            buildTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new BuildingTask(this, plan.getRotatedClipboard(getEnderChest().getFacing())) {
                public void run() {
                    BaseBlock bb;
                    for (int i=0;i<blocksPerTick;i++) {
                        if (moveCursor()) {
                            bb = clipboard.getPoint(cursor);
                            if (bb.getType() == 0) continue; // skip air blocks;
                            if (bb.getType() == Material.ENDER_CHEST.getId()) {
                                BuildInABox.getInstance().debug("Found EnderChest@" + cursor + "...");
                                if (worldCursor.getBlock().hasMetadata("buildInABox")) {
                                    BuildInABox.getInstance().debug("... Skipping");
                                    continue;
                                } else {
                                    BuildInABox.getInstance().debug("not a biab, processing.");
                                }
                            }
                            if (bb instanceof TileEntityBlock) {
                              BlockState state = worldCursor.getBlock().getState();
                              BaseBlock worldBlock = bukkitWorld.getBlock(new Vector(worldCursor.getBlockX(), worldCursor.getBlockY(), worldCursor.getBlockZ()));
                              BuildInABox.getInstance().debug("Replacing clipboard data with: " + worldBlock);
                              clipboard.setBlock(cursor, worldBlock);
                              if (state instanceof org.bukkit.inventory.InventoryHolder) {
                                  org.bukkit.inventory.InventoryHolder chest = (org.bukkit.inventory.InventoryHolder) state;
                                  Inventory inven = chest.getInventory();
                                  if (chest instanceof Chest) {
                                      inven = ((Chest) chest).getBlockInventory();
                                  }
                                  inven.clear();
                              }
                            }
                            if (cursor.getBlockY() < -clipboard.getOffset().getBlockY()) {
                                if (data.getReplacedBlocks().containsKey(cursor)) {
                                    BaseBlock replacement = data.getReplacedBlocks().get(cursor);
                                    if (replacement != null) {
                                        BuildInABox.getInstance().debug("Setting " + cursor + " to " + replacement);
                                        worldCursor.getBlock().setTypeIdAndData(replacement.getType(), (byte) replacement.getData(), false);
                                    }
                                } else {
                                    worldCursor.getBlock().setTypeIdAndData(0,(byte) 0, false);
                                }
                            } else {
                                BuildInABox.getInstance().debug("Setting " + cursor + " to air");
                                worldCursor.getBlock().setTypeIdAndData(0, (byte)0, false);
                            }
                            worldCursor.getBlock().removeMetadata("biab-block", BuildInABox.getInstance());
                            worldCursor.getBlock().removeMetadata("buildInABox", BuildInABox.getInstance());
                        } else {
                            // finished
                            // Rotate back to north to get the proper container coordinates
                            clipboard.rotate2D(BuildingPlan.getRotationDegrees(getEnderChest().getFacing(), BlockFace.NORTH));
                            Vector v;
                            for (int x=0;x<clipboard.getSize().getBlockX();x++) {
                                for (int z=0;z<clipboard.getSize().getBlockZ();z++) {
                                    for (int y=0;y<clipboard.getSize().getBlockY();y++) {
                                        v = new Vector(x,y,z);
                                        if (x == -clipboard.getOffset().getBlockX() && y == -clipboard.getOffset().getBlockY() && z == -clipboard.getOffset().getBlockZ()) {
                                            BuildInABox.getInstance().debug("Skipping enderchest in TileEntity check");
                                            continue;
                                        }
                                        bb = clipboard.getPoint(v);
                                        if (bb instanceof TileEntityBlock) {
                                            TileEntityBlock teb = bb;
                                            HashMap<String, Tag> values = new HashMap<String, Tag>();
                                            if (teb.getNbtData() != null && teb.getNbtData().getValue() != null) {
                                                for (Entry<String, Tag> e: teb.getNbtData().getValue().entrySet()) {
                                                    values.put(e.getKey(), e.getValue());
                                                }
                                                CompoundTag tag = new CompoundTag("", values);
                                                data.setTileEntities(new BlockVector(x,y,z), tag);
                                            }
                                        }
                                    }
                                }
                            }
                            getBlock().setType(Material.AIR);
                            getBlock().removeMetadata("buildInABox", plugin);
                            getBlock().removeMetadata("biab-block", plugin);
                            data.getLocation().getWorld().dropItem(new Location(data.getLocation().getWorld(), data.getLocation().getX() + 0.5, data.getLocation().getY() + 0.5, data.getLocation().getZ() + 0.5), data.toItemStack());
                            data.setLocation(null);
                            data.setLastActivity(System.currentTimeMillis());
                            data.setReplacedBlocks(null);
                            plugin.getDataStore().saveChest(data);
                            buildTask.cancel();
                            return;
                        }
                    }

                }
            }, 1, 1);
        } else {
            player.sendMessage(ChatColor.GOLD + getPlan().getName() + " is locked by " + ChatColor.GOLD + getLockedBy());
        }
    }

    class LockingTask implements Runnable {
        public boolean cancelled = false;
        String lockingPlayer;
        long totalTime;
        long startTime;
        public LockingTask(String playerName, long totalTimeSeconds) {
            this.startTime = System.currentTimeMillis();
            this.totalTime = totalTimeSeconds * 1000;
            this.lockingPlayer = playerName;
        }

        protected String getCancelMessage() {
            return ChatColor.RED + "Locking attempt cancelled.";
        }

        protected String getSuccessMessage() {
            return ChatColor.GREEN + "Successfully Locked " + getPlan().getName() + ".";
        }

        protected String getProgressMessage(int percentage) {
            return ChatColor.GOLD + "Locking " + getPlan().getName() + "... " + percentage + "%";
        }

        protected String getLockedBy() {
            return lockingPlayer;
        }

        public void cancel() {
            Player player = plugin.getServer().getPlayer(lockingPlayer);
            cancelled = true;
            if (player.isOnline()) {
                player.sendMessage(getCancelMessage());
                data.setLockedBy(getLockedBy() == null ? lockingPlayer : null);
                lockingTask = null;
            }
        }

        public void run() {
            if (cancelled) return;
            Player player = plugin.getServer().getPlayer(lockingPlayer);
            if (!player.isOnline()) {
                cancel();
            } else {
                // check distance from chest;
                try {
                    double distance = player.getLocation().distance(data.getLocation());
                    if (distance > plugin.getConfig().getDouble("max-locking-distance", 5)) {
                        cancel();
                        return;
                    }
                } catch (IllegalArgumentException ex) {
                    // Cross-world distance check
                    cancel();
                    return;
                }
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > totalTime) elapsed = totalTime;
                int pct = (int)Math.floor((elapsed / (double) totalTime) * 100);
                if (pct < 100) {
                    player.sendMessage(getProgressMessage(pct));
                    plugin.getServer().getScheduler().runTaskLater(plugin, this, 20);
                } else {
                    data.setLockedBy(getLockedBy());
                    lockingTask = null;
                    player.sendMessage(getSuccessMessage());
                }
            }
        }
    }

    class UnlockingTask extends LockingTask {
        public UnlockingTask(String playerName, long totalTime) {
            super(playerName, totalTime);
        }

        @Override
        public String getCancelMessage() {
            return ChatColor.RED + "Unlock attempt Cancelled.";
        }

        @Override
        public String getSuccessMessage() {
            return ChatColor.GREEN + "Successfully Unlocked " + getPlan().getName() + ".";
        }

        @Override
        public String getProgressMessage(int percentage) {
            return ChatColor.GOLD + "Unlocking " + getPlan().getName() + "... " + percentage + "%";
        }

        @Override
        public String getLockedBy() {
            return null;
        }
    }

    public String[] getDescription() {
        List<String> desc = new ArrayList<String>(2);
        desc.add(ChatColor.GOLD + getPlan().getName() + " - " + (previewing ? ChatColor.GREEN + "PREVIEW" : (isLocked() ? ChatColor.RED + "LOCKED " + ChatColor.WHITE + "[" + ChatColor.GOLD + data.getLockedBy() + ChatColor.WHITE + "]" : ChatColor.GREEN + "UNLOCKED")));
        if (previewing) {
            desc.add(ChatColor.GOLD + "Left click to cancel " + ChatColor.WHITE + "|" + ChatColor.GOLD + " Right click to confirm");
        } else if (isLocked()) {
            desc.add(ChatColor.GOLD + "Right click twice to unlock");
        } else {
            desc.add(ChatColor.GOLD + "Left click twice to pick up " + ChatColor.WHITE + "|" + ChatColor.GOLD + " Right click twice to lock");
        }
        String[] sa = new String[desc.size()];
        return desc.toArray(sa);
    }
}
