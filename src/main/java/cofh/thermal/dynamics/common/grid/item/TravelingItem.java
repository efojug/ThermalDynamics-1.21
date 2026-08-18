package cofh.thermal.dynamics.common.grid.item;

import cofh.lib.util.Utils;
import cofh.thermal.dynamics.common.attachment.ItemServoAttachment;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class TravelingItem {

    public static final int DUCT_LENGTH = 100;
    public static final int IMPULSE_SPEED = DUCT_LENGTH;
    public static final int STUFF_AFTER = 100;
    public static final int DROP_AFTER = 200;

    private ItemStack stack;
    private final List<Direction> path = new ArrayList<>();
    private int routeIndex;
    private int progress;
    private int age;
    private int speed;
    private BlockPos source = BlockPos.ZERO;
    private Direction sourceSide = Direction.DOWN;
    private BlockPos destination = BlockPos.ZERO;
    private Direction destinationSide = Direction.DOWN;
    private Direction incomingDirection = Direction.UP;
    private boolean goingToStuff;
    private boolean waitForFirstTick;
    private long lastServerTick = Long.MIN_VALUE;
    private long lastClientTick = Long.MIN_VALUE;

    public TravelingItem(ItemStack stack, BlockPos source, Direction sourceSide, ItemRoute route) {

        this.stack = stack.copy();
        this.source = source;
        this.sourceSide = sourceSide;
        this.incomingDirection = sourceSide.getOpposite();
        this.speed = IMPULSE_SPEED;
        this.waitForFirstTick = true;
        setRoute(route);
    }

    private TravelingItem() {

    }

    public ItemStack stack() {

        return stack;
    }

    public int progress() {

        return progress;
    }

    public int speed() {

        return speed;
    }

    public Direction direction() {

        if (routeIndex < path.size()) {
            return path.get(routeIndex);
        }
        return destinationSide;
    }

    public Direction incomingDirection() {

        return incomingDirection;
    }

    public void setSpeed(int speed) {

        this.speed = Math.max(1, speed);
    }

    public void tick(ItemDuctBlockEntity duct) {

        if (stack.isEmpty()) {
            duct.removeTravelingItem(this);
            return;
        }
        long gameTime = duct.getLevel().getGameTime();
        if (lastServerTick == gameTime) {
            return;
        }
        lastServerTick = gameTime;
        ++age;
        if (waitForFirstTick) {
            waitForFirstTick = false;
            return;
        }
        if (routeIndex >= path.size()) {
            progress += speed;
            if (progress < DUCT_LENGTH) {
                return;
            }
            progress %= DUCT_LENGTH;
            finishAtEndpoint(duct);
            return;
        }
        progress += speed;
        if (progress < DUCT_LENGTH) {
            return;
        }
        Direction direction = path.get(routeIndex);
        ItemDuctBlockEntity next = duct.getConnectedDuct(direction);
        if (next == null) {
            reroute(duct);
            return;
        }
        progress %= DUCT_LENGTH;
        incomingDirection = direction;
        ++routeIndex;
        duct.removeTravelingItem(this);
        next.addTravelingItem(this);
    }

    public void clientTick(ItemDuctBlockEntity duct) {

        if (stack.isEmpty()) {
            duct.removeTravelingItem(this);
            return;
        }
        long gameTime = duct.getLevel().getGameTime();
        if (lastClientTick == gameTime) {
            return;
        }
        lastClientTick = gameTime;
        if (routeIndex >= path.size()) {
            if (progress + speed >= DUCT_LENGTH) {
                duct.removeTravelingItem(this);
            } else {
                progress += speed;
            }
            return;
        }
        progress += speed;
        if (progress < DUCT_LENGTH) {
            return;
        }
        ItemDuctBlockEntity next = duct.getAdjacentItemDuct(path.get(routeIndex));
        if (next == null) {
            progress = DUCT_LENGTH - 1;
            return;
        }
        progress %= DUCT_LENGTH;
        incomingDirection = path.get(routeIndex);
        ++routeIndex;
        duct.removeTravelingItem(this);
        next.addTravelingItem(this);
    }

    private void finishAtEndpoint(ItemDuctBlockEntity duct) {

        if (goingToStuff && duct.getBlockPos().equals(source)) {
            if (duct.getAttachment(sourceSide) instanceof ItemServoAttachment servo) {
                servo.stuff(stack);
                duct.removeTravelingItem(this);
                return;
            }
        } else if (!goingToStuff) {
            stack = duct.insertIntoEndpoint(destinationSide, stack);
            if (stack.isEmpty()) {
                duct.removeTravelingItem(this);
                return;
            }
        }
        reroute(duct);
    }

    private void reroute(ItemDuctBlockEntity duct) {

        ItemGrid grid = duct.getGrid();
        ItemRoute route = grid.findRoute(duct.getBlockPos(), duct.getBlockPos().equals(source) ? sourceSide : null, stack,
                goingToStuff ? null : destination, goingToStuff ? null : destinationSide);
        if (route != null) {
            setRoute(route);
            progress = 0;
            return;
        }
        if (age >= STUFF_AFTER && !goingToStuff) {
            ItemRoute stuffRoute = grid.findRouteToDuct(duct.getBlockPos(), source, sourceSide);
            if (stuffRoute != null) {
                goingToStuff = true;
                setRoute(stuffRoute);
                progress = 0;
                return;
            }
        }
        if (age >= DROP_AFTER) {
            Utils.dropDismantleStackIntoWorld(stack, duct.getLevel(), duct.getBlockPos());
            duct.removeTravelingItem(this);
        } else {
            progress = 0;
        }
    }

    private void setRoute(ItemRoute route) {

        path.clear();
        path.addAll(route.steps());
        routeIndex = 0;
        destination = route.destination();
        destinationSide = route.destinationSide();
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {

        tag.put("Stack", stack.save(provider, new CompoundTag()));
        byte[] sides = new byte[path.size()];
        for (int i = 0; i < path.size(); ++i) {
            sides[i] = (byte) path.get(i).ordinal();
        }
        tag.putByteArray("Path", sides);
        tag.putInt("RouteIndex", routeIndex);
        tag.putInt("Progress", progress);
        tag.putInt("Age", age);
        tag.putInt("Speed", speed);
        tag.putLong("Source", source.asLong());
        tag.putByte("SourceSide", (byte) sourceSide.ordinal());
        tag.putLong("Destination", destination.asLong());
        tag.putByte("DestinationSide", (byte) destinationSide.ordinal());
        tag.putByte("IncomingDirection", (byte) incomingDirection.ordinal());
        tag.putBoolean("GoingToStuff", goingToStuff);
        return tag;
    }

    public static TravelingItem load(CompoundTag tag, HolderLookup.Provider provider) {

        TravelingItem item = new TravelingItem();
        item.stack = ItemStack.parseOptional(provider, tag.getCompound("Stack"));
        for (byte side : tag.getByteArray("Path")) {
            if (side >= 0 && side < Direction.values().length) {
                item.path.add(Direction.values()[side]);
            }
        }
        item.routeIndex = Math.max(0, Math.min(tag.getInt("RouteIndex"), item.path.size()));
        item.progress = Math.max(0, tag.getInt("Progress"));
        item.age = Math.max(0, tag.getInt("Age"));
        item.speed = IMPULSE_SPEED;
        item.source = BlockPos.of(tag.getLong("Source"));
        item.sourceSide = getDirection(tag.getByte("SourceSide"));
        item.destination = BlockPos.of(tag.getLong("Destination"));
        item.destinationSide = getDirection(tag.getByte("DestinationSide"));
        item.incomingDirection = getDirection(tag.getByte("IncomingDirection"));
        item.goingToStuff = tag.getBoolean("GoingToStuff");
        return item;
    }

    private static Direction getDirection(byte ordinal) {

        return ordinal >= 0 && ordinal < Direction.values().length ? Direction.values()[ordinal] : Direction.DOWN;
    }

}
