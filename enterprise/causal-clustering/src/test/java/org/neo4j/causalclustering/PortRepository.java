/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering;

import static org.neo4j.causalclustering.PortConstants.EphemeralPortMaximum;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PortRepository
{
    private final Path directory;

    private int currentPort;

    public PortRepository( Path directory, int initialPort )
    {
        this.directory = directory;

        this.currentPort = initialPort;
    }

    // synchronize between threads in this JVM
    public synchronized int reserveNextPort()
    {
        while ( currentPort <= EphemeralPortMaximum )
        {
            Path portFilePath = directory.resolve( "port" + currentPort );

            // fail fast
            if ( Files.exists( portFilePath ) )
            {
                currentPort++;
                continue;
            }

            try
            {
                File portFile = portFilePath.toFile();

                // take a lock, do a test, plant a flag. Cannot. possibly. fail.
                try ( FileOutputStream fileOutputStream = new FileOutputStream( portFile, true ) )
                {
                    // synchronize between processes running parallel builds
                    FileLock fileLock = fileOutputStream.getChannel().tryLock();

                    if ( fileLock == null )
                    {
                        currentPort++;
                        continue;
                    }

                    // fail fast
                    if (portFile.length() != 0)
                    {
                        currentPort++;
                        continue;
                    }

                    fileOutputStream.write( "Hallelujah!".getBytes() );
                    fileOutputStream.flush();
                }

                return currentPort++;
            }
            catch ( IOException e )
            {
                throw new IllegalStateException( "This will never happen - LWN", e );
            }
        }

        throw new IllegalStateException( "There are no more ephemeral/ dynamic ports available" );
    }
}
