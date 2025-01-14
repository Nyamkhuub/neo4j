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
package org.neo4j.cypher.internal.compiler.spi

import org.neo4j.cypher.internal.planner.spi.CardinalityByLabelsAndRelationshipType
import org.neo4j.cypher.internal.planner.spi.GraphStatistics
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor
import org.neo4j.cypher.internal.planner.spi.IndexSelectivity
import org.neo4j.cypher.internal.planner.spi.InstrumentedGraphStatistics
import org.neo4j.cypher.internal.planner.spi.MutableGraphStatisticsSnapshot
import org.neo4j.cypher.internal.planner.spi.NodesAllCardinality
import org.neo4j.cypher.internal.planner.spi.NodesWithLabelCardinality
import org.neo4j.cypher.internal.util.Cardinality
import org.neo4j.cypher.internal.util.LabelId
import org.neo4j.cypher.internal.util.PropertyKeyId
import org.neo4j.cypher.internal.util.RelTypeId
import org.neo4j.cypher.internal.util.Selectivity
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class GraphStatisticsSnapshotTest extends CypherFunSuite {

  private val label2 = LabelId(2)
  private val label4 = LabelId(4)
  private val index = IndexDescriptor.forLabel(IndexDescriptor.IndexType.Btree, LabelId(0), Seq(PropertyKeyId(3)))

  test("records queries and its observed values") {
    val snapshot = new MutableGraphStatisticsSnapshot()

    val allNodes = 500
    val indexSelectivity = 0.25
    val nodesWithLabel = 40
    val relationships = 5000

    val statistics = graphStatistics(allNodes = allNodes, idxSelectivity = indexSelectivity,
      relCardinality = relationships, labeledNodes = nodesWithLabel)
    val instrumentedStatistics = InstrumentedGraphStatistics(statistics, snapshot)
    instrumentedStatistics.nodesAllCardinality()
    instrumentedStatistics.uniqueValueSelectivity(index)
    instrumentedStatistics.nodesWithLabelCardinality(Some(label4))
    instrumentedStatistics.patternStepCardinality(Some(label2), None, None)

    snapshot.freeze.statsValues should equal(Map(
      NodesAllCardinality -> allNodes,
      IndexSelectivity(index) -> indexSelectivity,
      NodesWithLabelCardinality(Some(label4)) -> nodesWithLabel,
      CardinalityByLabelsAndRelationshipType(Some(label2), None, None) -> relationships
    ))
  }

  test("a snapshot shouldn't diverge from equal values") {
    val snapshot = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics = InstrumentedGraphStatistics(graphStatistics(), snapshot)
    instrumentedStatistics.nodesAllCardinality()
    instrumentedStatistics.uniqueValueSelectivity(index)
    instrumentedStatistics.nodesWithLabelCardinality(Some(label4))
    instrumentedStatistics.patternStepCardinality(Some(label2), None, None)
    instrumentedStatistics.patternStepCardinality(None, Some(RelTypeId(1)), Some(label2))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics()

    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    instrumentedStatistics2.patternStepCardinality(None, Some(RelTypeId(1)), Some(label2))
    instrumentedStatistics2.patternStepCardinality(Some(label2), None, None)
    instrumentedStatistics2.uniqueValueSelectivity(index)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))

    val frozen1 = snapshot.freeze
    val reallySensitiveDivergence: Double = 0.9

    frozen1.diverges(snapshot2.freeze).divergence should be < reallySensitiveDivergence
  }

  test("a snapshot shouldn't diverge from small differences") {
    val snapshot = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics = InstrumentedGraphStatistics(graphStatistics(allNodes = 1000), snapshot)
    instrumentedStatistics.nodesAllCardinality()

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(graphStatistics(allNodes = 1010), snapshot2)
    instrumentedStatistics2.nodesAllCardinality()

    val frozen1 = snapshot.freeze
    frozen1.diverges(snapshot2.freeze).divergence should (be > 0.001 and be < 0.01)
  }

  test("a snapshot should pick up divergences") {
    val snapshot1 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics()
    val instrumentedStatistics1 = InstrumentedGraphStatistics(statistics, snapshot1)
    instrumentedStatistics1.nodesAllCardinality()
    instrumentedStatistics1.uniqueValueSelectivity(index)
    instrumentedStatistics1.nodesWithLabelCardinality(Some(label4))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))

    statistics.factor(2)
    instrumentedStatistics2.uniqueValueSelectivity(index)

    val frozen1 = snapshot1.freeze
    val frozen2 = snapshot2.freeze
    val smallNumber = 0.1
    val bigNumber = 0.6

    frozen1.diverges(frozen2).divergence should (be > smallNumber and be < bigNumber)
  }

  test("0 selectivity values should not lead to wrong divergences") {
    val snapshot1 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics(idxSelectivity = 0, idxPropertyExistsSelectivity = 0)
    val instrumentedStatistics1 = InstrumentedGraphStatistics(statistics, snapshot1)
    instrumentedStatistics1.nodesAllCardinality()
    instrumentedStatistics1.uniqueValueSelectivity(index)
    instrumentedStatistics1.nodesWithLabelCardinality(Some(label4))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    statistics.factor(2)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))
    instrumentedStatistics2.uniqueValueSelectivity(index)

    val frozen1 = snapshot1.freeze
    val frozen2 = snapshot2.freeze
    val smallNumber = 0.1

    frozen1.diverges(frozen2).divergence should be > smallNumber
  }

  test("0 label cardinality values should not lead to wrong divergences") {
    val snapshot1 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics(labeledNodes = 0)
    val instrumentedStatistics1 = InstrumentedGraphStatistics(statistics, snapshot1)
    instrumentedStatistics1.nodesAllCardinality()
    instrumentedStatistics1.uniqueValueSelectivity(index)
    instrumentedStatistics1.nodesWithLabelCardinality(Some(label4))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    statistics.factor(2)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))
    instrumentedStatistics2.uniqueValueSelectivity(index)

    val frozen1 = snapshot1.freeze
    val frozen2 = snapshot2.freeze
    val smallNumber = 0.1

    frozen1.diverges(frozen2).divergence should be > smallNumber
  }

  test("0 all nodes cardinality values should not lead to wrong divergences") {
    val snapshot1 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics(allNodes = 0)
    val instrumentedStatistics1 = InstrumentedGraphStatistics(statistics, snapshot1)
    instrumentedStatistics1.nodesAllCardinality()
    instrumentedStatistics1.uniqueValueSelectivity(index)
    instrumentedStatistics1.nodesWithLabelCardinality(Some(label4))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    statistics.factor(2)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))
    instrumentedStatistics2.uniqueValueSelectivity(index)

    val frozen1 = snapshot1.freeze
    val frozen2 = snapshot2.freeze
    val smallNumber = 0.1

    frozen1.diverges(frozen2).divergence should be > smallNumber
  }

  test("if threshold is 1.0 nothing diverges") {
    val snapshot1 = new MutableGraphStatisticsSnapshot()
    val statistics = graphStatistics()
    val instrumentedStatistics1 = InstrumentedGraphStatistics(statistics, snapshot1)
    instrumentedStatistics1.nodesAllCardinality()
    instrumentedStatistics1.uniqueValueSelectivity(index)
    instrumentedStatistics1.nodesWithLabelCardinality(Some(label4))

    val snapshot2 = new MutableGraphStatisticsSnapshot()
    val instrumentedStatistics2 = InstrumentedGraphStatistics(statistics, snapshot2)
    instrumentedStatistics2.nodesAllCardinality()
    instrumentedStatistics2.nodesWithLabelCardinality(Some(label4))

    statistics.factor(Long.MaxValue)
    instrumentedStatistics2.uniqueValueSelectivity(index)

    val frozen1 = snapshot1.freeze
    val frozen2 = snapshot2.freeze

    frozen1.diverges(frozen2).divergence should not (be < 1.0)
  }

  private def graphStatistics(allNodes: Long = 500,
                              labeledNodes: Long = 500,
                              relCardinality: Long = 5000,
                              idxSelectivity: Double = 1,
                              idxPropertyExistsSelectivity: Double = 1): TestGraphStatistics =
    new TestGraphStatistics(allNodes,
      labeledNodes,
      relCardinality,
      idxSelectivity,
      idxPropertyExistsSelectivity)

  class TestGraphStatistics(allNodes: Long,
                            labeledNodes: Long,
                            relCardinality: Long ,
                            idxSelectivity: Double ,
                            idxPropertyExistsSelectivity: Double) extends GraphStatistics {
    private var _factor: Double = 1L

    def nodesWithLabelCardinality(labelId: Option[LabelId]): Cardinality = labelId match {
      case None => Cardinality.SINGLE
      case Some(id) => Cardinality(labeledNodes * _factor)
    }

    def patternStepCardinality(fromLabel: Option[LabelId], relTypeId: Option[RelTypeId], toLabel: Option[LabelId]): Cardinality =
      Cardinality(relCardinality * _factor)

    def uniqueValueSelectivity(index: IndexDescriptor): Option[Selectivity] = {
      Selectivity.of(idxSelectivity / _factor)
    }

    def indexPropertyIsNotNullSelectivity(index: IndexDescriptor): Option[Selectivity] =
      Selectivity.of(idxPropertyExistsSelectivity / _factor)

    def factor(factor: Double): Unit = {
      _factor = factor
    }

    override def nodesAllCardinality(): Cardinality = Cardinality(allNodes * _factor)
  }
}
