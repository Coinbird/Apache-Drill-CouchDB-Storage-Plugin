
package org.apache.drill.exec.store.couch;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.exec.planner.logical.DrillOptiq;
import org.apache.drill.exec.planner.logical.DrillParseContext;
import org.apache.drill.exec.planner.logical.RelOptHelper;
import org.apache.drill.exec.planner.physical.FilterPrel;
import org.apache.drill.exec.planner.physical.PrelUtil;
import org.apache.drill.exec.planner.physical.ScanPrel;
import org.apache.drill.exec.store.StoragePluginOptimizerRule;
import org.apache.drill.shaded.guava.com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CouchPushDownFilterForScan extends StoragePluginOptimizerRule {
    private static final Logger logger = LoggerFactory
            .getLogger(CouchPushDownFilterForScan.class);
    public static final StoragePluginOptimizerRule INSTANCE = new CouchPushDownFilterForScan();

    private CouchPushDownFilterForScan() {
        super(
                RelOptHelper.some(FilterPrel.class, RelOptHelper.any(ScanPrel.class)),
                "CouchPushDownFilterForScan");
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        logger.info("start onmatch");
        final ScanPrel scan = (ScanPrel) call.rel(1);
        final FilterPrel filter = (FilterPrel) call.rel(0);
        final RexNode condition = filter.getCondition();

        CouchGroupScan groupScan = (CouchGroupScan) scan.getGroupScan();
        if (groupScan.isFilterPushedDown()) {
            return;
        }

        LogicalExpression conditionExp = DrillOptiq.toDrill(
                new DrillParseContext(PrelUtil.getPlannerSettings(call.getPlanner())), scan, condition);
        CouchFilterBuilder couchFilterBuilder = new CouchFilterBuilder(groupScan, conditionExp);
        CouchScanSpec newScanSpec = couchFilterBuilder.parseTree();
        if (newScanSpec == null) {
            logger.info("no filter pushdown so nothing to apply.");
            return; // no filter pushdown so nothing to apply.
        }

        CouchGroupScan newGroupsScan = null;
        newGroupsScan = new CouchGroupScan(groupScan.getUserName(), groupScan.getStorageConfig(),
                newScanSpec,groupScan.getColumns());
        newGroupsScan.setFilterPushedDown(true);
        logger.debug("assign new group scan with spec {}", newScanSpec);
        final ScanPrel newScanPrel = ScanPrel.create(scan, filter.getTraitSet(),
                newGroupsScan, scan.getRowType());
        if (couchFilterBuilder.isAllExpressionsConverted()) {
            /*
             * Since we could convert the entire filter condition expression into an
             * Mongo filter, we can eliminate the filter operator altogether.
             */
            call.transformTo(newScanPrel);
        } else {
            call.transformTo(filter.copy(filter.getTraitSet(),
                    ImmutableList.of((RelNode) newScanPrel)));
        }
        call.transformTo(newScanPrel);
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        final ScanPrel scan = (ScanPrel) call.rel(1);
        if (scan.getGroupScan() instanceof CouchGroupScan) {
            return super.matches(call);
        }
        logger.debug("couch matches return false {}", scan.getGroupScan());
        return false;
    }
}

