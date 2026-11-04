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

import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.util.ThreadPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test that adds white noise to one control channel's samples, in steps, and logs how its decoding holds up at each
 * step.  It measures how much noise floor rise a channel can take before it stops decoding.
 *
 * Started by a request file with one key=value per line:
 * <pre>
 * owner=| Medina | Medina           (end of the channel's "system | site | name")
 * step=180                         (seconds per level)
 * levels=off -60 -50 -40 -30 off   (noise power in dBFS, or off)
 * </pre>
 * The file is removed when the run starts.  A cancel file next to it ends the run and turns the noise off.
 */
public final class SimulatedNoise
{
    private static final Logger mLog = LoggerFactory.getLogger(SimulatedNoise.class);
    private static final Set<String> OWNERS = ConcurrentHashMap.newKeySet();
    private static volatile String sOwnerSuffix;
    private static volatile float sSigma;
    private static ScheduledFuture<?> sWatcher;
    private static volatile boolean sRunning;

    private SimulatedNoise()
    {
    }

    /**
     * Watches for the request file.
     */
    public static synchronized void start(Path requestFile)
    {
        if(sWatcher == null)
        {
            Path cancelFile = requestFile.resolveSibling(requestFile.getFileName() + ".cancel");
            sWatcher = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> check(requestFile, cancelFile), 10, 10,
                TimeUnit.SECONDS);
        }
    }

    public static synchronized void stop()
    {
        if(sWatcher != null)
        {
            sWatcher.cancel(false);
            sWatcher = null;
        }

        sSigma = 0;
        sOwnerSuffix = null;
    }

    /**
     * Sample transform for the owner's channel that adds the current noise when the owner is under test.
     */
    public static UnaryOperator<ComplexSamples> transform(String owner)
    {
        OWNERS.add(owner);

        return samples -> {
            String suffix = sOwnerSuffix;
            float sigma = sSigma;

            if(suffix == null || sigma <= 0 || !owner.endsWith(suffix))
            {
                return samples;
            }

            ThreadLocalRandom random = ThreadLocalRandom.current();
            float[] i = new float[samples.i().length];
            float[] q = new float[samples.q().length];

            for(int x = 0; x < i.length; x++)
            {
                i[x] = samples.i()[x] + (float)random.nextGaussian() * sigma;
                q[x] = samples.q()[x] + (float)random.nextGaussian() * sigma;
            }

            return new ComplexSamples(i, q, samples.timestamp());
        };
    }

    private static void check(Path requestFile, Path cancelFile)
    {
        try
        {
            if(!sRunning && Files.exists(requestFile))
            {
                List<String> lines = Files.readAllLines(requestFile);
                Files.deleteIfExists(requestFile);
                Files.deleteIfExists(cancelFile);
                String owner = null;
                long step = 180;
                List<String> levels = new ArrayList<>();

                for(String line: lines)
                {
                    int split = line.indexOf('=');

                    if(split < 0)
                    {
                        continue;
                    }

                    String key = line.substring(0, split).trim();
                    String value = line.substring(split + 1).trim();

                    switch(key)
                    {
                        case "owner" -> owner = value;
                        case "step" -> step = Long.parseLong(value);
                        case "levels" -> levels.addAll(List.of(value.split("\\s+")));
                        default -> {}
                    }
                }

                if(owner == null || owner.isEmpty() || levels.isEmpty())
                {
                    mLog.warn("Simulated noise request needs owner and levels - ignored");
                    return;
                }

                String suffix = owner;
                long seconds = step;
                sRunning = true;
                Thread thread = new Thread(() -> run(suffix, seconds, levels, cancelFile), "sdrtrunk simulated noise");
                thread.setDaemon(true);
                thread.start();
            }
        }
        catch(Throwable t)
        {
            mLog.error("Error reading simulated noise request", t);
        }
    }

    private static void run(String suffix, long stepSeconds, List<String> levels, Path cancelFile)
    {
        try
        {
            mLog.info("Simulated noise run starting on channels ending [" + suffix + "] - " + levels + " at " +
                stepSeconds + " s per level");

            for(String level: levels)
            {
                double dbfs = level.equalsIgnoreCase("off") ? Double.NEGATIVE_INFINITY : Double.parseDouble(level);
                List<String> owners = OWNERS.stream().filter(owner -> owner.endsWith(suffix)).toList();
                List<DecodeQuality.Sample> before = owners.stream().map(DecodeQuality::get).toList();
                sOwnerSuffix = suffix;
                sSigma = Double.isInfinite(dbfs) ? 0 : (float)Math.sqrt(Math.pow(10, dbfs / 10) / 2);

                for(long waited = 0; waited < stepSeconds; waited++)
                {
                    if(Files.exists(cancelFile))
                    {
                        mLog.info("Simulated noise run cancelled");
                        return;
                    }

                    Thread.sleep(1000);
                }

                for(int x = 0; x < owners.size(); x++)
                {
                    DecodeQuality.Sample sample = DecodeQuality.get(owners.get(x)).minus(before.get(x));
                    Double signal = DecodeQuality.getSignalLevel(owners.get(x));
                    mLog.info(String.format("Simulated noise %s: [%s] - %.1f valid/s, %d failed, %.2f corrected bits " +
                        "per message, %d sync loss bits, signal plus noise %s",
                        Double.isInfinite(dbfs) ? "off" : String.format("%.0f dBFS", dbfs), owners.get(x),
                        sample.valid() / (double)stepSeconds, sample.invalid(), sample.correctedBitsPerMessage(),
                        sample.syncLossBits(), signal == null || signal.isInfinite() ? "unknown" :
                            String.format("%.1f dB", signal)));
                }
            }

            mLog.info("Simulated noise run finished");
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        catch(Throwable t)
        {
            mLog.error("Error during simulated noise run", t);
        }
        finally
        {
            sSigma = 0;
            sOwnerSuffix = null;
            sRunning = false;
        }
    }
}
