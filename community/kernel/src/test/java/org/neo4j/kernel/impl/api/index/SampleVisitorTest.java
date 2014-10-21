/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.api.index;

import static org.junit.Assert.assertEquals;
import static org.neo4j.register.Register.DoubleLongRegister;

import org.junit.Test;
import org.neo4j.register.Registers;

public class SampleVisitorTest
{
    private final Object value = new Object();

    @Test
    public void shouldSampleNothing()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 10 );

        // when
        // nothing has been sampled

        // then
        assertSampledValues( sampler, 0, 0 );
    }

    @Test
    public void shouldSampleASingleValue()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 10 );

        // when
        sampler.include( value );

        // then
        assertSampledValues( sampler, 1, 1 );

    }

    @Test
    public void shouldSampleDuplicateValues()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 10 );

        // when
        sampler.include( value );
        sampler.include( value );
        sampler.include( new Object() );

        // then
        assertSampledValues( sampler, 2, 3 );
    }

    @Test
    public void shouldDivideTheSamplingInStepsNotBiggerThanBatchSize()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 1 );

        // when
        sampler.include( value );
        sampler.include( value );
        sampler.include( new Object() );

        // then
        assertSampledValues( sampler, 1, 1 );
    }

    @Test
    public void shouldExcludeValuesFromTheCurrentSampling()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 10 );
        sampler.include( value );
        sampler.include( value );
        sampler.include( new Object() );

        // when
        sampler.exclude( value );

        // then
        assertSampledValues( sampler, 2, 2 );
    }

    @Test
    public void shouldDoNothingWhenExcludingAValueInAnEmptySample()
    {
        // given
        SampleVisitor sampler = new SampleVisitor( 10 );

        // when
        sampler.exclude( value );
        sampler.include( value );

        // then
        assertSampledValues( sampler, 1, 1 );
    }

    private void assertSampledValues( SampleVisitor sampler, long expectedUniqueValues, long expectedSampledSize )
    {
        final DoubleLongRegister register = Registers.newDoubleLongRegister();
        sampler.result( register );
        assertEquals( expectedUniqueValues, register.readFirst() );
        assertEquals( expectedSampledSize, register.readSecond() );
    }
}
