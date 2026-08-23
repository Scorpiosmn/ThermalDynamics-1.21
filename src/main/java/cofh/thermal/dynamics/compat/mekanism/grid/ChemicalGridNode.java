package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.common.grid.ContentGridNode;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;

public class ChemicalGridNode extends ContentGridNode<ChemicalGrid, ChemicalStack, IChemicalHandler> {

    protected ChemicalGridNode(ChemicalGrid grid) {

        super(grid);
    }

    @Override
    protected BlockCapability<IChemicalHandler, Direction> capability() {

        return CHEMICAL_HANDLER;
    }

    @Override
    protected long fill(IChemicalHandler handler, ChemicalStack stack, long amount, boolean execute) {

        return amount - handler.insertChemical(stack.copyWithAmount(amount), execute ? Action.EXECUTE : Action.SIMULATE).getAmount();
    }

    @Override
    protected long handlerContentTotal(IChemicalHandler handler) {

        long total = 0;
        for (int tank = 0; tank < handler.getChemicalTanks(); ++tank) {
            long amount = handler.getChemicalInTank(tank).getAmount();
            total = Long.MAX_VALUE - total < amount ? Long.MAX_VALUE : total + amount;
        }
        return total;
    }

    @Override
    protected boolean handlerContainsSame(IChemicalHandler handler, ChemicalStack stack) {

        for (int tank = 0; tank < handler.getChemicalTanks(); ++tank) {
            ChemicalStack inTank = handler.getChemicalInTank(tank);
            if (!inTank.isEmpty() && ChemicalStack.isSameChemical(inTank, stack)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEmptyStack(ChemicalStack stack) {

        return stack.isEmpty();
    }

}
