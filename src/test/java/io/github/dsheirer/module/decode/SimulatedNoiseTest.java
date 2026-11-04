/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class SimulatedNoiseTest
{
    @Test
    void addsNoiseAtTheRequestedLevelOnlyToTheNamedChannel() throws Exception
    {
        UnaryOperator<ComplexSamples> medina = SimulatedNoise.transform("MARCS | Medina | Medina");
        UnaryOperator<ComplexSamples> parma = SimulatedNoise.transform("Parma | Medina | Parma Medina");
        ComplexSamples silence = new ComplexSamples(new float[200_000], new float[200_000], 0);

        Path dir = Files.createTempDirectory("simulated-noise");
        Path request = dir.resolve("simulated_noise.request");
        Files.writeString(request, "owner=| Medina | Medina\nstep=30\nlevels=-30\n");
        SimulatedNoise.start(request);

        try
        {
            for(int x = 0; x < 300 && Files.exists(request); x++)
            {
                Thread.sleep(100);
            }

            Thread.sleep(200);
            ComplexSamples noisy = medina.apply(silence);
            double power = 0;

            for(int x = 0; x < noisy.i().length; x++)
            {
                power += noisy.i()[x] * noisy.i()[x] + noisy.q()[x] * noisy.q()[x];
            }

            assertEquals(-30, 10 * Math.log10(power / noisy.i().length), 0.2);
            assertSame(silence, parma.apply(silence));
        }
        finally
        {
            Files.writeString(dir.resolve("simulated_noise.request.cancel"), "");
            SimulatedNoise.stop();
        }

        assertTrue(Files.notExists(request));
    }
}
