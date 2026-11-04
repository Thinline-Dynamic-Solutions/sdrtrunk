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
package io.github.dsheirer.module.decode.p25.phase2;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractVoiceTimeslot;
import io.github.dsheirer.sample.Listener;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs what a phase 2 traffic channel decoded over its lifetime: valid and failed messages, voice timeslots, sync loss
 * and signal level, so a traffic channel that starts but produces no audio can be told apart from one that never starts.
 */
public class P25P2TrafficDiagnostics extends Module implements IMessageListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P2TrafficDiagnostics.class);
    private final String mDescription;
    private final LongAdder mValid = new LongAdder();
    private final LongAdder mInvalid = new LongAdder();
    private final LongAdder mVoice = new LongAdder();
    private final LongAdder mSyncLossBits = new LongAdder();
    private final Set<String> mMacTypes = ConcurrentHashMap.newKeySet();
    private volatile Double mSignalLevel;
    private long mStarted;

    /**
     * @param description of the channel: name, frequency, timeslot, talkgroup and scramble parameter state
     */
    public P25P2TrafficDiagnostics(String description)
    {
        mDescription = description;
    }

    public void setSignalLevel(double level)
    {
        mSignalLevel = level;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return message -> {
            if(message instanceof SyncLossMessage syncLoss)
            {
                mSyncLossBits.add(syncLoss.getBitsProcessed());
            }
            else if(message != null && message.isValid())
            {
                mValid.increment();

                if(message instanceof AbstractVoiceTimeslot)
                {
                    mVoice.increment();
                }
                else if(message instanceof MacMessage macMessage && macMessage.getMacStructure() != null &&
                    mMacTypes.size() < 16)
                {
                    mMacTypes.add("TS" + message.getTimeslot() + " " +
                        macMessage.getMacStructure().getClass().getSimpleName());
                }
            }
            else if(message != null)
            {
                mInvalid.increment();
            }
        };
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
        mStarted = System.currentTimeMillis();
        mLog.info("Phase 2 traffic channel started: " + mDescription);
    }

    @Override
    public void stop()
    {
        Double level = mSignalLevel;
        mLog.info(String.format("Phase 2 traffic channel stopped after %.1f s: %s - %d valid, %d failed, %d voice, " +
                "%d sync loss bits, signal %s, MAC %s", (System.currentTimeMillis() - mStarted) / 1000.0, mDescription,
            mValid.sum(), mInvalid.sum(), mVoice.sum(), mSyncLossBits.sum(),
            level == null ? "unknown" : String.format("%.1f dB", level), new TreeSet<>(mMacTypes)));
    }
}
