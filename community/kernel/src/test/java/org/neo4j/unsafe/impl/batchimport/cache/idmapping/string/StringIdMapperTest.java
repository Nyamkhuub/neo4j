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
package org.neo4j.unsafe.impl.batchimport.cache.idmapping.string;

import java.util.Random;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import org.neo4j.unsafe.impl.batchimport.cache.GatheringMemoryStatsVisitor;
import org.neo4j.unsafe.impl.batchimport.cache.LongArrayFactory;
import org.neo4j.unsafe.impl.batchimport.cache.MemoryStatsVisitor;
import org.neo4j.unsafe.impl.batchimport.cache.idmapping.IdMapper;

import static java.lang.System.currentTimeMillis;

import static org.junit.Assert.fail;

public class StringIdMapperTest
{
    @Test
    public void shouldHandleGreatAmountsOfStuff() throws Exception
    {
        // GIVEN
        IdMapper idMapper = new StringIdMapper( LongArrayFactory.AUTO );

        // WHEN
        int hundredThousands = 3;
        long index = 0;
        log( "Putting..." );
        for ( long m = 0; m < hundredThousands; m++ )
        {
            for ( long i = 0; i < 100_000; i++, index++ )
            {
                String string = randomUUID();
                idMapper.put( string, index );
            }
            log( "put " + m + " million" );
        }
        log( "Sorting..." );
        idMapper.prepare();
        MemoryStatsVisitor memoryStats = new GatheringMemoryStatsVisitor();
        idMapper.visitMemoryStats( memoryStats );

        // THEN
        resetRandomness();
        log( "Reading..." );
        for ( long m = 0; m < hundredThousands; m++ )
        {
            for ( long i = 0; i < 100_000; i++, index++ )
            {
                // the UUIDs here will be generated in the same sequence as above because we reset the random
                String string = randomUUID();
                if ( idMapper.get( string ) == -1 )
                {
                    fail( "Couldn't find " + string + " even though I added it just previously" );
                }
            }
            log( "read " + m + " million" );
        }
    }

    private String randomUUID()
    {
        random.nextBytes( scratchBytes );
        return UUID.nameUUIDFromBytes( scratchBytes ).toString();
    }

    private final long seed = currentTimeMillis();
    private Random random;
    private final byte[] scratchBytes = new byte[20];

    @Before
    public void before() throws Exception
    {
        resetRandomness();
    }

    private void resetRandomness() throws Exception
    {
        random = new Random( seed );
    }

    private void log( String string )
    {
//        System.out.println( time() + ": " + string );
    }
}
