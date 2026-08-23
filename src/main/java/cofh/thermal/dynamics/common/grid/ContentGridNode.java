package cofh.thermal.dynamics.common.grid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;

/**
 * A grid node that pushes a single content type (fluid, chemical) into adjacent external handlers.
 * Owns the cached per-side connections and the round-robin distribution/transmission algorithms;
 * subclasses supply the capability, the handler fill call, and access to the grid's stored content
 * and overflow buffer.
 *
 * @param <S> the content stack type
 * @param <H> the external handler capability type
 */
public abstract class ContentGridNode<G extends Grid<G, ?>, S, H> extends GridNode<G> implements ITickableGridNode {

    protected final List<Connection> distList = new ArrayList<>(6);
    protected int distIndex;

    protected ContentGridNode(G grid) {

        super(grid);
    }

    // region TYPE HOOKS
    protected abstract BlockCapability<H, Direction> capability();

    /** Offers up to {@code amount} of {@code stack} to the handler and returns the accepted amount. */
    protected abstract long fill(H handler, S stack, long amount, boolean execute);

    /** Total content currently stored across the handler's tanks, saturating at Long.MAX_VALUE. */
    protected abstract long handlerContentTotal(H handler);

    /** Whether the handler currently holds content of the same type as {@code stack}. */
    protected abstract boolean handlerContainsSame(H handler, S stack);

    protected abstract boolean isEmptyStack(S stack);
    // endregion

    protected void cacheConnections() {

        Level world = getWorld();
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        connections.clear();
        distList.clear();
        for (Direction dir : DIRECTIONS) {
            BlockPos targetPos = pos.relative(dir);
            if (world.isLoaded(targetPos) && grid.canConnectOnSide(targetPos, dir.getOpposite())) {
                connections.add(dir);
            }
        }
        for (Direction dir : connections) {
            distList.add(new Connection(serverLevel, dir, pos.relative(dir)));
        }
        cached = true;
    }

    @Override
    public void distributionTick() {

        // Output distribution runs centrally in BufferedContentGrid.distributeOutput(), which
        // splits the held content equally across every endpoint connection of the grid.
    }

    /** Number of cached external connections; refreshes the connection cache when stale. */
    public int outputConnectionCount() {

        if (!cached) {
            cacheConnections();
        }
        return distList.size();
    }

    /** The host duct used for per-side filtering during a distribution pass. Nullable. */
    public IDuct<?, ?> hostDuct() {

        return gridHost();
    }

    /** Offers up to {@code amount} of {@code stack} to the indexed connection; returns the accepted amount. */
    public long fillConnection(IDuct<?, ?> host, int index, S stack, long amount, boolean execute) {

        return fillDir(host, distList.get(index), stack, amount, execute, null);
    }

    /**
     * Whether any of this node's connections, excluding {@code inserter} and {@code excluded},
     * currently holds content of the same type as {@code stack}. Read-only probe used by the
     * external-push gate to treat momentarily full destinations as valid routes.
     */
    public boolean containsSameContent(S stack, BlockPos inserter, Set<BlockPos> excluded) {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return false;
        }
        for (Connection connection : distList) {
            if (connection.targetPos.equals(inserter) || excluded.contains(connection.targetPos)) {
                continue;
            }
            if (duct.getConnectionType(connection.direction) == DISABLED) {
                continue;
            }
            IAttachment attachment = duct.getAttachment(connection.direction);
            // An input-only side (servo) can never receive deliveries, so content behind it is not a route.
            if (!attachment.allowsGridOutput()) {
                continue;
            }
            if (connection.consumeInvalidation()) {
                attachment.invalidate();
            }
            H handler = attachment.wrapExternalCapability(capability(), connection.capabilityCache.getCapability());
            if (handler != null && handlerContainsSame(handler, stack)) {
                return true;
            }
        }
        return false;
    }

    /** Round-robin push of {@code amount} of {@code stack} into adjacent external handlers. Returns the accepted amount. */
    public long transmit(S stack, long amount, boolean execute, @Nullable Set<BlockPos> visitedTargets) {
        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null || distList.isEmpty() || isEmptyStack(stack) || amount <= 0) {
            return 0;
        }
        long remaining = amount;
        int tempIndex = distIndex;
        ++distIndex;
        distIndex %= distList.size();
        for (int i = distIndex; i < distList.size() && remaining > 0; ++i) {
            remaining -= fillDir(duct, distList.get(i), stack, remaining, execute, visitedTargets);
        }
        for (int i = 0; i < distIndex && remaining > 0; ++i) {
            remaining -= fillDir(duct, distList.get(i), stack, remaining, execute, visitedTargets);
        }
        if (!execute) {
            distIndex = tempIndex;
        }
        return amount - remaining;
    }

    private long fillDir(IDuct<?, ?> duct, Connection connection, S stack, long amount, boolean execute, @Nullable Set<BlockPos> visitedTargets) {

        if (isEmptyStack(stack) || amount <= 0) {
            return 0;
        }
        Direction dir = connection.direction;
        if (duct.getConnectionType(dir) == DISABLED) {
            return 0;
        }
        if (visitedTargets != null && visitedTargets.contains(connection.targetPos)) {
            return 0;
        }
        // Never deliver content back to the block that pushed it into the grid.
        if (grid instanceof BufferedContentGrid<?, ?, ?> buffered && buffered.isContentOrigin(connection.targetPos)) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        if (!attachment.allowsGridOutput()) {
            return 0;
        }
        if (connection.consumeInvalidation()) {
            attachment.invalidate();
        }
        H handler = attachment.wrapExternalCapability(capability(), connection.capabilityCache.getCapability());
        if (handler == null) {
            return 0;
        }
        long accepted;
        if (execute) {
            long before = handlerContentTotal(handler);
            accepted = fill(handler, stack, amount, true);
            long after = handlerContentTotal(handler);
            // Cross-check the claimed acceptance against the destination's actual content delta:
            // the grid drains what fill() reports, so a destination that claims-but-voids destroys
            // content in a way the grid-side ledger alone cannot see.
            if (before != Long.MAX_VALUE && after != Long.MAX_VALUE && after - before != accepted) {
                cofh.thermal.dynamics.ThermalDynamics.LOG.warn("Grid delivery mismatch at {}: destination claimed {} but its content changed by {}",
                        connection.targetPos, accepted, after - before);
            }
            if (accepted > 0) {
                cofh.thermal.dynamics.ThermalDynamics.LOG.debug("Grid delivered {} of {} offered to {}", accepted, amount, connection.targetPos);
            }
            if (accepted > 0 && grid instanceof BufferedContentGrid<?, ?, ?> buffered) {
                buffered.auditNoteOut(accepted);
            }
        } else {
            accepted = fill(handler, stack, amount, false);
        }
        if (accepted > 0 && visitedTargets != null) {
            visitedTargets.add(connection.targetPos);
        }
        return accepted;
    }

    protected final class Connection {

        private final Direction direction;
        private final BlockPos targetPos;
        private final BlockCapabilityCache<H, Direction> capabilityCache;
        private boolean invalidated;

        private Connection(ServerLevel world, Direction direction, BlockPos targetPos) {

            this.direction = direction;
            this.targetPos = targetPos;
            capabilityCache = BlockCapabilityCache.create(capability(), world, targetPos, direction.getOpposite(),
                    () -> cached && isLoaded(), () -> invalidated = true);
        }

        private boolean consumeInvalidation() {

            boolean result = invalidated;
            invalidated = false;
            return result;
        }

    }

}
