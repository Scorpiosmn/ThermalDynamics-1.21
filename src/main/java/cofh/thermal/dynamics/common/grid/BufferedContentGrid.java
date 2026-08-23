package cofh.thermal.dynamics.common.grid;

import cofh.thermal.dynamics.ThermalDynamics;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import com.google.common.graph.EndpointPair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base for grids that hold a single buffered content type (fluid, chemical): one shared storage
 * tank per grid, a long-domain overflow buffer, round-robin distribution to nodes, debounced
 * render sync, and proportional redistribution on merge/split. Content access goes through the
 * {@link OverflowBuffer.Ops} type-class, so this class never references a concrete stack type and
 * stays usable from optional-dependency modules.
 * <p>
 * Subclasses keep their storage implementation and public handler API; this class supplies the
 * shared algorithms via the abstract content hooks.
 */
public abstract class BufferedContentGrid<G extends BufferedContentGrid<G, N, S>, N extends ContentGridNode<G, S, ?>, S> extends Grid<G, N> {

    protected static final String TAG_STORAGE = "Storage";
    protected static final String TAG_OVERFLOW = "Overflow";
    protected static final String TAG_ORIGINS = "Origins";

    protected final OverflowBuffer.Ops<S> contentOps;
    protected final OverflowBuffer<S> overflowBuffer;
    protected final GridRenderState<S> renderState;
    protected final OverflowWatchdog overflowWatchdog;
    private final String contentName;

    protected List<N> distList = List.of();
    protected int distIndex;
    protected List<N> attachmentNodeList = List.of();
    protected boolean attachmentNodesDirty = true;
    protected List<N> nodeList = List.of();
    protected int nodeTracker;
    protected boolean isSendingContent;
    protected boolean isReplayingOverflow;
    protected final ObjectOpenHashSet<BlockPos> visitedTargets = new ObjectOpenHashSet<>();
    // Reused scratch state for the per-tick equal-split output pass.
    private final List<N> outputNodes = new ArrayList<>();
    private final List<IDuct<?, ?>> outputHosts = new ArrayList<>();
    private final IntArrayList candidateNode = new IntArrayList();
    private final IntArrayList candidateConn = new IntArrayList();

    protected BufferedContentGrid(IGridType<G> gridType, UUID id, Level world, OverflowBuffer.Ops<S> ops, long renderAmount, String contentName) {

        super(gridType, id, world);
        this.contentOps = ops;
        this.contentName = contentName;
        this.overflowBuffer = new OverflowBuffer<>(ops);
        this.renderState = new GridRenderState<>(ops, renderAmount);
        this.overflowWatchdog = new OverflowWatchdog(contentName);
    }

    // region CONTENT HOOKS
    /** The stack currently held by the grid's storage tank (not the overflow buffer). */
    protected abstract S storedStack();

    /** Replaces the storage tank content with {@code amount} of {@code type} (empty type or non-positive amount clears it). */
    protected abstract void setStored(S type, long amount);

    protected abstract long storageCapacity();

    protected abstract void setStorageCapacity(long capacity);

    /** Storage capacity contributed by each duct block of this grid. */
    protected abstract long ductCapacity();

    protected abstract int renderAlpha(S held);

    protected abstract CompoundTag saveStorage(HolderLookup.Provider provider);

    protected abstract void loadStorage(HolderLookup.Provider provider, CompoundTag tag);

    /** Inserts into the storage tank; returns the accepted amount. */
    protected abstract long storageInsert(S resource, boolean execute);

    /** Extracts from the storage tank; returns the extracted amount. */
    protected abstract long storageExtract(long amount, boolean execute);

    /** Hook for reading pre-{@code TAG_STORAGE} save layouts; default is a no-op. */
    protected void readLegacyStorage(HolderLookup.Provider provider, CompoundTag nbt) {

    }
    // endregion

    // region CONTENT ORIGINS
    // External blocks that inserted the currently held content. The grid never delivers content
    // back to these positions, so a block that pushes into the duct (an AE2 pattern provider, a
    // source tank) cannot receive its own content back. The set is an episode: it resets when the
    // grid runs empty before the next external insert, and on topology changes. Deliberately
    // transient - not saved to NBT.
    protected final ObjectOpenHashSet<BlockPos> contentOrigins = new ObjectOpenHashSet<>();

    /** Marks an external inserter for the current content episode; returns true if newly added. */
    public final boolean markContentOrigin(BlockPos externalPos) {

        if (heldAmountLong() <= 0) {
            contentOrigins.clear();
        }
        return contentOrigins.add(externalPos.immutable());
    }

    public final void unmarkContentOrigin(BlockPos externalPos) {

        contentOrigins.remove(externalPos);
    }

    public final boolean isContentOrigin(BlockPos pos) {

        return !contentOrigins.isEmpty() && contentOrigins.contains(pos);
    }

    protected final void resetContentOrigins() {

        contentOrigins.clear();
    }

    /**
     * Simulates how much of {@code resource} the grid could deliver to external endpoints other
     * than {@code inserter} and the current content origins. Used as the acceptance gate for
     * external pushes: content with no route forward is rejected outright (like a rejected pipe
     * connection) instead of parking in the duct with no way to ever leave.
     */
    public final long simulateRoutable(S resource, long amount, BlockPos inserter) {

        if (amount <= 0 || contentOps.isEmpty(resource)) {
            return 0;
        }
        List<N> list = nodeList;
        if (list.size() != getNodes().size()) {
            list = List.copyOf(getNodes().values());
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.isEmpty()) {
            return 0;
        }
        visitedTargets.clear();
        visitedTargets.add(inserter);
        visitedTargets.addAll(contentOrigins);
        isSendingContent = true;
        long toSend = amount;
        try {
            for (N node : list) {
                if (!node.isLoaded()) {
                    continue;
                }
                toSend -= node.transmit(resource, toSend, false, visitedTargets);
                if (toSend <= 0) {
                    break;
                }
            }
        } finally {
            isSendingContent = false;
            visitedTargets.clear();
        }
        return amount - toSend;
    }
    /**
     * Acceptance gate for external pushes. Content is accepted when it matches what the grid
     * already carries (fast path - continuous flows skip the network walk entirely), when it could
     * be delivered somewhere right now, or when a reachable endpoint already contains the same
     * content - a momentarily full destination still counts as a route, so the duct keeps its
     * buffering role. Only content with no route at all is rejected.
     */
    public final boolean isExternallyAcceptable(S resource, BlockPos inserter) {

        if (contentOps.isEmpty(resource)) {
            return false;
        }
        S held = heldContent();
        if (!contentOps.isEmpty(held)) {
            // Mismatched types are rejected by the insert itself; matching types joined an episode
            // that was already routable when it started.
            return contentOps.sameType(held, resource);
        }
        if (simulateRoutable(resource, contentOps.amount(resource), inserter) > 0) {
            return true;
        }
        return endpointContainsSame(resource, inserter);
    }

    /** Whether any reachable endpoint besides {@code inserter} and the origins holds the same content. */
    private boolean endpointContainsSame(S resource, BlockPos inserter) {

        List<N> list = nodeList;
        if (list.size() != getNodes().size()) {
            list = List.copyOf(getNodes().values());
            nodeList = list;
            nodeTracker = 0;
        }
        for (N node : list) {
            if (node.isLoaded() && node.containsSameContent(resource, inserter, contentOrigins)) {
                return true;
            }
        }
        return false;
    }
    // endregion

    // region CONTENT TRANSFER
    /**
     * Shared insert algorithm: type check, reentrancy guard, headroom clamp, storage first, then
     * direct distribution of the overflow to endpoints. Returns the accepted amount.
     */
    protected final long insertContent(S resource, boolean execute) {

        long amount = contentOps.amount(resource);
        if (contentOps.isEmpty(resource) || amount <= 0 || isSendingContent) {
            return 0;
        }
        S held = heldContent();
        if (!contentOps.isEmpty(held) && !contentOps.sameType(held, resource)) {
            return 0;
        }
        if (!isReplayingOverflow) {
            long headroom = overflowHeadroom();
            if (headroom <= 0) {
                return 0;
            }
            if (amount > headroom) {
                amount = headroom;
                resource = contentOps.withAmount(resource, headroom);
            }
        }
        long added = storageInsert(resource, execute);
        long overflow = amount - added;
        long sent = overflow <= 0 ? 0 : distributeOverflow(resource, overflow, execute);
        long accepted = added + sent;
        if (execute && !isReplayingOverflow && accepted > 0) {
            auditNoteIn(accepted);
            ThermalDynamics.LOG.debug("{} grid {} insert: offered {} added {} sent {}", contentName, getId(), amount, added, sent);
        }
        return accepted;
    }

    /** Drains held content, overflow buffer first, then storage; returns the drained amount. */
    protected final long extractContent(long amount, boolean execute) {

        if (amount <= 0) {
            return 0;
        }
        long drained;
        if (overflowBuffer.isEmpty()) {
            drained = storageExtract(amount, execute);
        } else {
            long pending = contentOps.amount(overflowBuffer.peek(amount));
            if (execute) {
                overflowBuffer.drain(pending);
            }
            drained = pending + (amount > pending ? storageExtract(amount - pending, execute) : 0);
        }
        if (execute && !isDrainingHeld) {
            auditNoteOut(drained);
        }
        return drained;
    }

    /** Drains content already delivered by {@link #distributeOutput()}; not an external extraction. */
    protected final void drainHeld(long amount) {

        isDrainingHeld = true;
        try {
            extractContent(amount, true);
        } finally {
            isDrainingHeld = false;
        }
    }

    /** Re-offers parked overflow to storage and endpoints; returns the amount moved out of the buffer. */
    public final long replayOverflow() {

        S offered = overflowBuffer.peek(Long.MAX_VALUE);
        if (contentOps.isEmpty(offered)) {
            return 0;
        }
        long accepted;
        isReplayingOverflow = true;
        try {
            accepted = insertContent(offered, true);
        } finally {
            isReplayingOverflow = false;
        }
        overflowBuffer.drain(accepted);
        return accepted;
    }

    /**
     * Executes an insert and parks any unaccepted remainder in the overflow buffer. For callers
     * that have already irrevocably extracted the content from its source (servos); a shortfall
     * beyond storage, endpoints and buffer is logged.
     */
    public final long insertOrPark(S resource) {

        long amount = contentOps.amount(resource);
        long accepted = insertContent(resource, true);
        long leftover = amount - accepted;
        if (leftover <= 0) {
            return accepted;
        }
        long parked = overflowBuffer.add(resource, leftover);
        auditNoteIn(parked);
        noteOverflowParked();
        if (parked < leftover) {
            ThermalDynamics.LOG.warn("{} overflow buffer rejected {}", contentName, leftover - parked);
        }
        return accepted + parked;
    }

    /** Gate and origin bookkeeping for a push from the block at {@code inserter}; returns the accepted amount. */
    public final long externalInsert(S resource, boolean execute, BlockPos inserter) {

        if (contentOps.isEmpty(resource) || !isExternallyAcceptable(resource, inserter)) {
            return 0;
        }
        if (!execute) {
            return insertContent(resource, false);
        }
        boolean added = markContentOrigin(inserter);
        long accepted = insertContent(resource, true);
        if (added && accepted <= 0) {
            unmarkContentOrigin(inserter);
        }
        return accepted;
    }
    // endregion

    // region CONTENT AUDIT
    // Conservation ledger: heldAmountLong() must change by exactly (external inserts - external
    // deliveries/extractions) between ticks. Any imbalance is a duplication or void bug; the log
    // line identifies the tick and direction so in-game reports can be traced to a code path.
    protected long auditBaseline = Long.MIN_VALUE;
    protected long auditIn;
    protected long auditOut;
    protected boolean isDrainingHeld;

    /** Records content entering held storage from an external party (insert accepted, overflow parked). */
    public final void auditNoteIn(long amount) {

        if (amount > 0) {
            auditIn += amount;
        }
    }

    /** Records content leaving held storage to an external party (endpoint fill, external extraction). */
    public final void auditNoteOut(long amount) {

        if (amount > 0) {
            auditOut += amount;
        }
    }

    /** Invalidates the ledger across topology/content redistribution; skips exactly one check. */
    protected final void auditInvalidate() {

        auditBaseline = Long.MIN_VALUE;
        auditIn = 0;
        auditOut = 0;
    }

    private void auditCheck() {

        long held = heldAmountLong();
        if (auditBaseline != Long.MIN_VALUE) {
            long expected = auditBaseline + auditIn - auditOut;
            if (held != expected) {
                ThermalDynamics.LOG.error("{} grid {} content imbalance: held {} but expected {} (baseline {} + in {} - out {}) -> {} {}",
                        contentName, getId(), held, expected, auditBaseline, auditIn, auditOut,
                        expected > held ? "LOST" : "GAINED", Math.abs(expected - held));
            }
        }
        auditBaseline = held;
        auditIn = 0;
        auditOut = 0;
    }
    // endregion

    // region TICK
    @Override
    public void tick() {

        auditCheck();
        if (distList.size() != getNodes().size()) {
            distList = List.copyOf(getNodes().values());
        }
        if (attachmentNodesDirty) {
            rebuildAttachmentNodeList();
        }
        for (N node : attachmentNodeList) {
            if (node.isLoaded()) {
                node.attachmentTick();
            }
        }
        renderUpdate();
        overflowWatchdog.check(world, getId(), overflowBuffer);
        distributeOutput();
    }

    /**
     * Per-tick output distribution: the held content is split mathematically equally across every
     * endpoint connection of the grid (base share each, the remainder units go to a rotating window
     * of connections), then whatever endpoints rejected is re-offered once in rotating order so
     * uneven acceptance still reaches full throughput. Replaces the old round-robin walk where the
     * first-served connections could drain the whole grid.
     */
    private void distributeOutput() {

        S held = heldContent();
        if (contentOps.isEmpty(held)) {
            return;
        }
        long total = heldAmountLong();
        if (total <= 0) {
            return;
        }
        outputNodes.clear();
        outputHosts.clear();
        candidateNode.clear();
        candidateConn.clear();
        int connectionTotal = 0;
        for (N node : distList) {
            if (!node.isLoaded()) {
                continue;
            }
            int count = node.outputConnectionCount();
            if (count <= 0) {
                continue;
            }
            IDuct<?, ?> host = node.hostDuct();
            if (host == null) {
                continue;
            }
            int ordinal = outputNodes.size();
            outputNodes.add(node);
            outputHosts.add(host);
            for (int connection = 0; connection < count; ++connection) {
                candidateNode.add(ordinal);
                candidateConn.add(connection);
            }
            connectionTotal += count;
        }
        if (connectionTotal == 0) {
            return;
        }
        int offset = Math.floorMod(distIndex++, connectionTotal);
        long base = total / connectionTotal;
        long extra = total % connectionTotal;
        long acceptedTotal = 0;
        isSendingContent = true;
        try {
            for (int i = 0; i < connectionTotal; ++i) {
                int rotated = i - offset;
                if (rotated < 0) {
                    rotated += connectionTotal;
                }
                long request = base + (rotated < extra ? 1 : 0);
                if (request <= 0) {
                    continue;
                }
                int ordinal = candidateNode.getInt(i);
                acceptedTotal += outputNodes.get(ordinal).fillConnection(outputHosts.get(ordinal), candidateConn.getInt(i), held, request, true);
            }
            long remaining = total - acceptedTotal;
            for (int step = 0; step < connectionTotal && remaining > 0; ++step) {
                int i = offset + step;
                if (i >= connectionTotal) {
                    i -= connectionTotal;
                }
                int ordinal = candidateNode.getInt(i);
                long accepted = outputNodes.get(ordinal).fillConnection(outputHosts.get(ordinal), candidateConn.getInt(i), held, remaining, true);
                remaining -= accepted;
                acceptedTotal += accepted;
            }
        } finally {
            isSendingContent = false;
            outputNodes.clear();
            outputHosts.clear();
        }
        if (acceptedTotal > 0) {
            ThermalDynamics.LOG.debug("{} grid {} output: total {} over {} connections, delivered {}", contentName, getId(), total, connectionTotal, acceptedTotal);
            drainHeld(acceptedTotal);
        }
    }

    private void renderUpdate() {

        S held = heldContent();
        if (renderState.tick(world, held, renderAlpha(held))) {
            updateHosts();
        }
    }

    protected final void rebuildAttachmentNodeList() {

        List<N> tickableNodes = new ArrayList<>();
        for (N node : getNodes().values()) {
            if (node.needsAttachmentTick()) {
                tickableNodes.add(node);
            }
        }
        attachmentNodeList = List.copyOf(tickableNodes);
        attachmentNodesDirty = false;
    }
    // endregion

    // region TOPOLOGY
    @Override
    public void onModified() {

        auditInvalidate();
        resetContentOrigins();
        distList = List.of();
        nodeList = List.of();
        attachmentNodeList = List.of();
        attachmentNodesDirty = true;
        renderState.requestUpdate();
        recalculateCapacity();
        super.onModified();
    }

    @Override
    public void onAttachmentsChanged() {

        attachmentNodesDirty = true;
    }

    @Override
    public boolean canMerge(G from) {

        S held = heldContent();
        S fromHeld = from.heldContent();
        if (!contentOps.isEmpty(held) && !contentOps.isEmpty(fromHeld) && !contentOps.sameType(held, fromHeld)) {
            return false;
        }
        return from.heldAmountLong() <= overflowHeadroom();
    }

    @Override
    public void onMerge(G from) {

        auditInvalidate();
        from.auditInvalidate();
        resetContentOrigins();
        from.resetContentOrigins();
        long total = heldAmountLong() + from.heldAmountLong();
        S type = contentOps.isEmpty(heldContent()) ? from.heldContent() : heldContent();
        overflowBuffer.clear();
        from.overflowBuffer.clear();
        recalculateCapacity();
        long intoStorage = Math.min(total, storageCapacity());
        setStored(type, intoStorage);
        long remainder = total - intoStorage;
        if (!contentOps.isEmpty(type) && remainder > 0) {
            overflowBuffer.add(type, remainder);
        }
        if (!overflowBuffer.isEmpty()) {
            noteOverflowParked();
        }
        renderState.requestUpdate();
        refreshCapabilities();
        from.refreshCapabilities();
    }

    @Override
    public void onSplit(List<G> others) {

        auditInvalidate();
        resetContentOrigins();
        long totalDucts = 0;
        for (G grid : others) {
            grid.auditInvalidate();
            grid.resetContentOrigins();
            totalDucts = saturatingAdd(totalDucts, grid.getDuctCountLong());
            grid.recalculateCapacity();
            grid.renderState.requestUpdate();
            grid.refreshCapabilities();
        }
        refreshCapabilities();
        renderState.requestUpdate();
        long total = heldAmountLong();
        if (contentOps.isEmpty(heldContent()) || totalDucts == 0 || total <= 0) {
            return;
        }
        S type = heldContent();
        long perDuct = total / totalDucts;
        long remainder = total % totalDucts;
        long placed = 0;
        for (G grid : others) {
            long share = saturatingMultiply(perDuct, grid.getDuctCountLong());
            long extra = Math.min(remainder, Math.max(0L, grid.overflowHeadroom()));
            share = saturatingAdd(share, extra);
            remainder -= extra;
            if (share <= 0) {
                continue;
            }
            long intoStorage = Math.min(share, grid.storageCapacity());
            grid.setStored(type, intoStorage);
            long intoBuffer = share - intoStorage;
            long parked = intoBuffer <= 0 ? 0 : grid.overflowBuffer.add(type, intoBuffer);
            placed += intoStorage + parked;
            if (parked > 0) {
                grid.noteOverflowParked();
            }
        }
        if (remainder > 0 && !others.isEmpty()) {
            long parked = others.get(0).overflowBuffer.add(type, remainder);
            placed += parked;
            if (parked > 0) {
                others.get(0).noteOverflowParked();
            }
        }
        if (placed != total) {
            ThermalDynamics.LOG.error("{} grid split placed {} of {}", contentName, placed, total);
        }
        overflowBuffer.clear();
        setStored(contentOps.empty(), 0);
    }
    // endregion

    // region NBT
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        CompoundTag tag = super.serializeNBT(provider);
        // Content data must be nested: stack serialization uses the "id" key, which would otherwise
        // overwrite the grid UUID that GridContainer stores under "id".
        tag.put(TAG_STORAGE, saveStorage(provider));
        if (!overflowBuffer.isEmpty()) {
            tag.put(TAG_OVERFLOW, overflowBuffer.serializeNBT(provider));
        }
        // Origins travel with the held content so a reload mid-flow cannot deliver in-transit
        // content back to its inserters.
        if (!contentOrigins.isEmpty()) {
            long[] origins = new long[contentOrigins.size()];
            int i = 0;
            for (BlockPos pos : contentOrigins) {
                origins[i++] = pos.asLong();
            }
            tag.putLongArray(TAG_ORIGINS, origins);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

        auditInvalidate();
        resetContentOrigins();
        super.deserializeNBT(provider, nbt);
        recalculateCapacity();
        if (nbt.contains(TAG_STORAGE, CompoundTag.TAG_COMPOUND)) {
            loadStorage(provider, nbt.getCompound(TAG_STORAGE));
        } else {
            readLegacyStorage(provider, nbt);
        }
        overflowBuffer.clear();
        if (nbt.contains(TAG_OVERFLOW, CompoundTag.TAG_COMPOUND)) {
            overflowBuffer.deserializeNBT(provider, nbt.getCompound(TAG_OVERFLOW));
        }
        S stored = storedStack();
        if (!overflowBuffer.isEmpty()) {
            if (!contentOps.isEmpty(stored) && !contentOps.sameType(stored, overflowBuffer.type())) {
                ThermalDynamics.LOG.error("{} grid {} loaded incompatible overflow; discarding it", contentName, getId());
                overflowBuffer.clear();
            } else if (overflowBuffer.getAmount() > Long.MAX_VALUE - contentOps.amount(stored)) {
                ThermalDynamics.LOG.error("{} grid {} loaded overflow beyond long headroom; truncating", contentName, getId());
                overflowBuffer.drain(overflowBuffer.getAmount() - (Long.MAX_VALUE - contentOps.amount(stored)));
            }
        }
        if (!overflowBuffer.isEmpty()) {
            noteOverflowParked();
        }
        for (long packed : nbt.getLongArray(TAG_ORIGINS)) {
            contentOrigins.add(BlockPos.of(packed));
        }
    }
    // endregion

    // region CONTENT
    /** The representative held stack: the storage content, or a view of the overflow when the tank is empty. */
    public final S heldContent() {

        return contentOps.isEmpty(storedStack()) ? overflowBuffer.peek(1) : storedStack();
    }

    public final long heldAmountLong() {

        return contentOps.amount(storedStack()) + overflowBuffer.getAmount();
    }

    public final long overflowHeadroom() {

        return Long.MAX_VALUE - heldAmountLong();
    }

    public final OverflowBuffer<S> getOverflowBuffer() {

        return overflowBuffer;
    }

    public final void noteOverflowParked() {

        overflowWatchdog.notePark(world);
    }

    public final int getRenderAlpha() {

        return renderState.renderAlpha();
    }

    /**
     * Round-robin distribution of overflow into the grid's node endpoints. Returns the amount
     * accepted. Reentrancy is the caller's concern via {@link #isSendingContent}.
     */
    protected final long distributeOverflow(S resource, long overflow, boolean execute) {

        List<N> list = nodeList;
        if (list.size() != getNodes().size()) {
            list = List.copyOf(getNodes().values());
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.isEmpty()) {
            return 0;
        }
        int tempTracker = nodeTracker;
        long toSend = overflow;
        visitedTargets.clear();
        isSendingContent = true;
        try {
            for (int i = nodeTracker; i < list.size() && toSend > 0; ++i) {
                N node = list.get(i);
                if (!node.isLoaded()) {
                    continue;
                }
                toSend -= node.transmit(resource, toSend, execute, visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            for (int i = 0; i < list.size() && i < nodeTracker && toSend > 0; ++i) {
                N node = list.get(i);
                if (!node.isLoaded()) {
                    continue;
                }
                toSend -= node.transmit(resource, toSend, execute, visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            if (toSend > 0) {
                ++nodeTracker;
            }
            if (nodeTracker >= list.size()) {
                nodeTracker = 0;
            }
            if (!execute) {
                nodeTracker = tempTracker;
            }
        } finally {
            isSendingContent = false;
            visitedTargets.clear();
        }
        return overflow - toSend;
    }

    protected final void recalculateCapacity() {

        setStorageCapacity(saturatingMultiply(getDuctCountLong(), ductCapacity()));
    }

    protected final long getDuctCountLong() {

        long count = nodeGraph.nodes().size();
        for (EndpointPair<N> edge : nodeGraph.edges()) {
            count = saturatingAdd(count, GridHelper.numBetween(edge.nodeU().getPos(), edge.nodeV().getPos()));
        }
        return count;
    }
    // endregion

    // region MATH
    protected static int saturatingInt(long value) {

        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    protected static long saturatingAdd(long first, long second) {

        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    protected static long saturatingMultiply(long first, long second) {

        return first == 0 || second == 0 || first <= Long.MAX_VALUE / second ? first * second : Long.MAX_VALUE;
    }
    // endregion

}
