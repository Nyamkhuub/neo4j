/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.compiler.planner.ProcedureCallProjection
import org.neo4j.cypher.internal.compiler.planner.logical.idp.BestResults
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.compiler.planner.logical.steps.aggregation
import org.neo4j.cypher.internal.compiler.planner.logical.steps.distinct
import org.neo4j.cypher.internal.compiler.planner.logical.steps.projection
import org.neo4j.cypher.internal.compiler.planner.logical.steps.skipAndLimit
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.CallSubqueryHorizon
import org.neo4j.cypher.internal.ir.DistinctQueryProjection
import org.neo4j.cypher.internal.ir.LoadCSVProjection
import org.neo4j.cypher.internal.ir.PassthroughAllHorizon
import org.neo4j.cypher.internal.ir.RegularQueryProjection
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.ir.UnwindProjection
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.exceptions.InternalException

/*
Planning event horizons means planning the WITH clauses between query patterns. Some of these clauses are inlined
away when going from a string query to a QueryGraph. The remaining WITHs are the ones containing ORDER BY/LIMIT,
aggregation and UNWIND.
 */
case object PlanEventHorizon extends EventHorizonPlanner {

  override def planHorizon(plannerQuery: SinglePlannerQuery,
                           incomingPlans: BestResults[LogicalPlan],
                           prevInterestingOrder: Option[InterestingOrder],
                           context: LogicalPlanningContext): BestResults[LogicalPlan] = {
    val pickBest: CandidateSelector = {
      val limitSelectivity = planMatch.limitSelectivityForRestOfQuery(plannerQuery, context)
      context.config.pickBestCandidate(context.withLimitSelectivity(limitSelectivity))
    }
    // This config will only plan Sort if there is a required order in this plannerQuery
    val sortIfSelfRequiredConfig = InterestingOrderConfig(plannerQuery.interestingOrder)
    // This config will even plan Sort if there is a required order in a tail plannerQuery
    val sortIfTailOrSelfRequiredConfig = InterestingOrderConfig.interestingOrderForPart(plannerQuery, isRhs = false, isHorizon = true)

    def planSortIfSelfRequired = planHorizonForPlan(plannerQuery, incomingPlans.bestResult, prevInterestingOrder, context, sortIfSelfRequiredConfig)
    def planSortIfTailOrSelfRequired = planHorizonForPlan(plannerQuery, incomingPlans.bestResult, prevInterestingOrder, context, sortIfTailOrSelfRequiredConfig)
    def maintainSort = incomingPlans.bestResultFulfillingReq.map(planHorizonForPlan(plannerQuery, _, prevInterestingOrder, context, sortIfTailOrSelfRequiredConfig))

    if (plannerQuery.interestingOrder.requiredOrderCandidate.nonEmpty) {
      val Some(bestOverall) = pickBest(Seq(planSortIfSelfRequired) ++ maintainSort, "best overall plan with horizon")
      BestResults(bestOverall, None)
    } else {
      val bestOverall = planSortIfSelfRequired
      val bestSorted = if (sortIfSelfRequiredConfig == sortIfTailOrSelfRequiredConfig) {
        None
      } else {
        pickBest(Seq(planSortIfTailOrSelfRequired) ++ maintainSort, "best sorted plan with horizon")
      }
      BestResults(bestOverall, bestSorted)
    }
  }

  private[logical] def planHorizonForPlan(query: SinglePlannerQuery,
                                          plan: LogicalPlan,
                                          previousInterestingOrder: Option[InterestingOrder],
                                          context: LogicalPlanningContext,
                                          interestingOrderConfig: InterestingOrderConfig): LogicalPlan = {
    val selectedPlan = context.config.applySelections(plan, query.queryGraph, interestingOrderConfig, context)
    // We only want to mark a planned Sort (or a projection for a Sort) as solved if the ORDER BY comes from the current horizon.
    val updateSolvedOrdering = query.interestingOrder.requiredOrderCandidate.nonEmpty

    val projectedPlan = query.horizon match {
      case aggregatingProjection: AggregatingQueryProjection =>
        val aggregationPlan = aggregation(selectedPlan, aggregatingProjection, interestingOrderConfig.orderToReport, previousInterestingOrder, context)
        // aggregation is the only case where sort happens after the projection. The provided order of the aggregation plan will include
        // renames of the projection, thus we need to rename this as well for the required order before considering planning a sort.
        val sorted = SortPlanner.ensureSortedPlanWithSolved(aggregationPlan, interestingOrderConfig, context, updateSolvedOrdering)
        val limited = skipAndLimit(sorted, query, context)
        if (aggregatingProjection.selections.isEmpty) {
          limited
        } else {
          val predicates = aggregatingProjection.selections.flatPredicates
          context.logicalPlanProducer.planHorizonSelection(limited, predicates, interestingOrderConfig, context)
        }

      case regularProjection: RegularQueryProjection =>
        val sorted = SortPlanner.ensureSortedPlanWithSolved(selectedPlan, interestingOrderConfig, context, updateSolvedOrdering)
        val limited = skipAndLimit(sorted, query, context)
        val projected =
          if (regularProjection.projections.isEmpty && query.tail.isEmpty) {
            context.logicalPlanProducer.planEmptyProjection(plan, context)
          } else {
            projection(limited, regularProjection.projections, Some(regularProjection.projections), context)
          }
        if (regularProjection.selections.isEmpty) {
          projected
        } else {
          val predicates = regularProjection.selections.flatPredicates
          context.logicalPlanProducer.planHorizonSelection(projected, predicates, interestingOrderConfig, context)
        }

      case distinctProjection: DistinctQueryProjection =>
        val distinctPlan = distinct(selectedPlan, distinctProjection, context)
        val sorted = SortPlanner.ensureSortedPlanWithSolved(distinctPlan, interestingOrderConfig, context, updateSolvedOrdering)
        val limited = skipAndLimit(sorted, query, context)
        if (distinctProjection.selections.isEmpty) {
          limited
        } else {
          val predicates = distinctProjection.selections.flatPredicates
          context.logicalPlanProducer.planHorizonSelection(limited, predicates, interestingOrderConfig, context)
        }

      case UnwindProjection(variable, expression) =>
        val projected = context.logicalPlanProducer.planUnwind(selectedPlan, variable, expression, context)
        SortPlanner.ensureSortedPlanWithSolved(projected, interestingOrderConfig, context, updateSolvedOrdering)

      case ProcedureCallProjection(call) =>
        val projected = context.logicalPlanProducer.planProcedureCall(plan, call, context)
        SortPlanner.ensureSortedPlanWithSolved(projected, interestingOrderConfig, context, updateSolvedOrdering)

      case LoadCSVProjection(variableName, url, format, fieldTerminator) =>
        val projected = context.logicalPlanProducer.planLoadCSV(plan, variableName, url, format, fieldTerminator, context)
        SortPlanner.ensureSortedPlanWithSolved(projected, interestingOrderConfig, context, updateSolvedOrdering)

      case PassthroughAllHorizon() =>
        val projected = context.logicalPlanProducer.planPassAll(plan, context)
        SortPlanner.ensureSortedPlanWithSolved(projected, interestingOrderConfig, context, updateSolvedOrdering)

      case CallSubqueryHorizon(callSubquery, correlated) =>
        val subqueryContext = if (correlated)
          context.withUpdatedLabelInfo(plan)
        else
          context
        val subPlan = plannerQueryPartPlanner.plan(callSubquery, subqueryContext)
        val projected = context.logicalPlanProducer.planSubquery(plan, subPlan, context, correlated)
        SortPlanner.ensureSortedPlanWithSolved(projected, interestingOrderConfig, context, updateSolvedOrdering)

      case _ =>
        throw new InternalException(s"Received QG with unknown horizon type: ${query.horizon}")
    }

    // We need to check if reads introduced in the horizon conflicts with future writes
    Eagerness.horizonReadWriteEagerize(projectedPlan, query, context)
  }
}
