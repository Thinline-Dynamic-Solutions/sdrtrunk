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

package io.github.dsheirer.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated rolling log for Thinline Radio Core / health / websocket activity.
 * Lives next to the application log as thinline_core.log and keeps daily history.
 */
public class ThinlineRadioLog
{
    private static final Logger mLog = LoggerFactory.getLogger(ThinlineRadioLog.class);
    private static final String THINLINE_LOGGER = "io.github.dsheirer.audio.broadcast.thinline";
    private static final String LOG_FILENAME = "thinline_core.log";
    private static final int LOG_MAX_HISTORY = 30;
    private static final String APPENDER_NAME = "THINLINE_CORE_FILE";

    private final UserPreferences mUserPreferences;
    private RollingFileAppender mRollingFileAppender;
    private Path mLogDirectory;

    public ThinlineRadioLog(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.DIRECTORY && mRollingFileAppender != null && mLogDirectory != null)
        {
            Path next = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog();
            if(!next.equals(mLogDirectory))
            {
                mLog.info("Thinline Radio log directory changed [{}] - restarting", next);
                stop();
                start();
            }
        }
    }

    public void start()
    {
        MyEventBus.getGlobalEventBus().register(this);

        if(mRollingFileAppender != null)
        {
            return;
        }

        mLogDirectory = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog();
        Path logfile = mLogDirectory.resolve(LOG_FILENAME);
        mLog.info("Thinline Radio Log File: {}", logfile);

        LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%-25(%d{yyyyMMdd HHmmss.SSS} [%thread]) %-5level %logger{40} - %msg%n");
        encoder.start();

        mRollingFileAppender = new RollingFileAppender();
        mRollingFileAppender.setContext(loggerContext);
        mRollingFileAppender.setAppend(true);
        mRollingFileAppender.setName(APPENDER_NAME);
        mRollingFileAppender.setEncoder(encoder);
        mRollingFileAppender.setFile(logfile.toString());

        ThresholdFilter thresholdFilter = new ThresholdFilter();
        thresholdFilter.setLevel(Level.INFO.toString());
        thresholdFilter.setContext(loggerContext);
        thresholdFilter.setName("thinline core threshold filter");
        thresholdFilter.start();
        mRollingFileAppender.addFilter(thresholdFilter);

        String pattern = logfile.toString().replace(LOG_FILENAME, "%d{yyyyMMdd}_" + LOG_FILENAME);
        TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy<>();
        rollingPolicy.setContext(loggerContext);
        rollingPolicy.setFileNamePattern(pattern);
        rollingPolicy.setMaxHistory(LOG_MAX_HISTORY);
        rollingPolicy.setParent(mRollingFileAppender);
        rollingPolicy.start();

        mRollingFileAppender.setRollingPolicy(rollingPolicy);
        mRollingFileAppender.start();

        ch.qos.logback.classic.Logger thinlineLogger = loggerContext.getLogger(THINLINE_LOGGER);
        thinlineLogger.setLevel(Level.INFO);
        thinlineLogger.setAdditive(true);
        thinlineLogger.addAppender(mRollingFileAppender);

        mLog.info("Thinline Radio logging started ({} day history)", LOG_MAX_HISTORY);
    }

    public void stop()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        if(mRollingFileAppender == null)
        {
            return;
        }

        mLog.info("Stopping Thinline Radio logging");
        LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger thinlineLogger = loggerContext.getLogger(THINLINE_LOGGER);
        thinlineLogger.detachAppender(mRollingFileAppender);
        mRollingFileAppender.stop();
        mRollingFileAppender = null;
    }
}
