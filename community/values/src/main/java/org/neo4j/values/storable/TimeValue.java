/*
 * Copyright (c) 2002-2018 "Neo Technology,"
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
package org.neo4j.values.storable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.helpers.collection.Pair;
import org.neo4j.values.AnyValue;
import org.neo4j.values.StructureBuilder;
import org.neo4j.values.ValueMapper;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.VirtualValues;

import static java.lang.Integer.parseInt;
import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;
import static org.neo4j.values.storable.DateTimeValue.parseZoneName;
import static org.neo4j.values.storable.DurationValue.SECONDS_PER_DAY;
import static org.neo4j.values.storable.LocalTimeValue.optInt;
import static org.neo4j.values.storable.LocalTimeValue.parseTime;

public final class TimeValue extends TemporalValue<OffsetTime,TimeValue>
{
    public static TimeValue time( OffsetTime time )
    {
        return new TimeValue( requireNonNull( time, "OffsetTime" ) );
    }

    public static TimeValue time( int hour, int minute, int second, int nanosOfSecond, String offset )
    {
        return time( hour, minute, second, nanosOfSecond, parseOffset( offset ) );
    }

    public static TimeValue time( int hour, int minute, int second, int nanosOfSecond, ZoneOffset offset )
    {
        return new TimeValue(
                OffsetTime.of( LocalTime.of( hour, minute, second, nanosOfSecond ), offset ) );
    }

    public static TimeValue time( long nanosOfDayUTC, ZoneId offset )
    {
        return new TimeValue( OffsetTime.ofInstant( Instant.ofEpochSecond( 0, nanosOfDayUTC ), offset ) );
    }

    public static TimeValue parse( CharSequence text, Supplier<ZoneId> defaultZone )
    {
        return parse( TimeValue.class, PATTERN, TimeValue::parse, text, defaultZone );
    }

    public static TimeValue parse( TextValue text, Supplier<ZoneId> defaultZone )
    {
        return parse( TimeValue.class, PATTERN, TimeValue::parse, text, defaultZone );
    }

    public static TimeValue now( Clock clock )
    {
        return new TimeValue( OffsetTime.now( clock ) );
    }

    public static TimeValue now( Clock clock, String timezone )
    {
        return now( clock.withZone( parseZoneName( timezone ) ) );
    }

    public static TimeValue build( MapValue map, Supplier<ZoneId> defaultZone )
    {
        return StructureBuilder.build( builder( defaultZone ), map );
    }

    public static TimeValue select( AnyValue from, Supplier<ZoneId> defaultZone )
    {
        return builder( defaultZone ).selectTime( from );
    }

    @Override
    boolean hasTime()
    {
        return true;
    }

    public static TimeValue truncate(
            TemporalUnit unit,
            TemporalValue input,
            MapValue fields,
            Supplier<ZoneId> defaultZone )
    {
        OffsetTime time = input.getTimePart( defaultZone );
        OffsetTime truncatedOT = time.truncatedTo( unit );

        if ( fields.size() == 0 )
        {
            return time( truncatedOT );
        }
        else
        {
            // Timezone needs some special handling, since the builder will shift keeping the instant instead of the local time
            Map<String,AnyValue> updatedFields = new HashMap<>( fields.size() + 1 );
            for ( Map.Entry<String,AnyValue> entry : fields.entrySet() )
            {
                if ( "timezone".equals( entry.getKey() ) )
                {
                    ZoneOffset currentOffset = ZonedDateTime.ofInstant( Instant.now(), timezoneOf( entry.getValue() ) ).getOffset();
                    truncatedOT = truncatedOT.withOffsetSameLocal( currentOffset );
                }
                else
                {
                    updatedFields.put( entry.getKey(), entry.getValue() );
                }
            }

            truncatedOT = updateFieldMapWithConflictingSubseconds( updatedFields, unit, truncatedOT );

            if ( updatedFields.size() == 0 )
            {
                return time( truncatedOT );
            }
            updatedFields.put( "time", time( truncatedOT ) );
            return build( VirtualValues.map( updatedFields ), defaultZone );
        }
    }

    static OffsetTime defaultTime( ZoneId zoneId )
    {
        return OffsetTime.of( Field.hour.defaultValue, Field.minute.defaultValue, Field.second.defaultValue, Field.nanosecond.defaultValue,
                ZoneOffset.of( zoneId.toString() ) );
    }

    static TimeBuilder<TimeValue> builder( Supplier<ZoneId> defaultZone )
    {
        return new TimeBuilder<TimeValue>( defaultZone )
        {
            @Override
            protected boolean supportsTimeZone()
            {
                return true;
            }

            @Override
            public TimeValue buildInternal()
            {
                boolean selectingTime = fields.containsKey( Field.time );
                boolean selectingTimeZone;
                OffsetTime result;
                if ( selectingTime )
                {
                    AnyValue time = fields.get( Field.time );
                    if ( !(time instanceof TemporalValue) )
                    {
                        throw new IllegalArgumentException( String.format( "Cannot construct time from: %s", time ) );
                    }
                    TemporalValue t = (TemporalValue) time;
                    result = t.getTimePart( defaultZone );
                    selectingTimeZone = t.supportsTimeZone();
                }
                else
                {
                    result = defaultTime( timezone() );
                    selectingTimeZone = false;
                }

                result = assignAllFields( result );
                if ( timezone != null )
                {
                    ZoneOffset currentOffset = ZonedDateTime.ofInstant( Instant.now(), timezone() ).getOffset();
                    if ( selectingTime && selectingTimeZone )
                    {
                        result = result.withOffsetSameInstant( currentOffset );
                    }
                    else
                    {
                        result = result.withOffsetSameLocal( currentOffset );
                    }
                }
                return time( result );
            }
            @Override
            protected TimeValue selectTime(
                    AnyValue temporal )
            {
                if ( !(temporal instanceof TemporalValue) )
                {
                    throw new IllegalArgumentException( String.format( "Cannot construct time from: %s", temporal ) );
                }
                if ( temporal instanceof TimeValue &&
                        timezone == null )
                {
                    return (TimeValue) temporal;
                }

                TemporalValue v = (TemporalValue) temporal;
                OffsetTime time = v.getTimePart( defaultZone );
                if ( timezone != null )
                {
                    ZoneOffset currentOffset = ZonedDateTime.ofInstant( Instant.now(), timezone() ).getOffset();
                    time = time.withOffsetSameInstant( currentOffset );
                }
                return time( time );
            }
        };
    }

    private final OffsetTime value;

    private TimeValue( OffsetTime value )
    {
        this.value = value;
    }

    @Override
    int unsafeCompareTo( Value otherValue )
    {
        TimeValue other = (TimeValue) otherValue;
        return value.compareTo( other.value );
    }

    @Override
    OffsetTime temporal()
    {
        return value;
    }

    @Override
    LocalDate getDatePart()
    {
        throw new IllegalArgumentException( String.format( "Cannot get the date of: %s", this ) );
    }

    @Override
    LocalTime getLocalTimePart()
    {
        return value.toLocalTime();
    }

    @Override
    OffsetTime getTimePart( Supplier<ZoneId> defaultZone )
    {
        return value;
    }

    @Override
    ZoneId getZoneId( Supplier<ZoneId> defaultZone )
    {
        return value.getOffset();
    }

    @Override
    ZoneId getZoneId()
    {
        throw new UnsupportedTemporalTypeException( "Cannot get the timezone of" + this );
    }

    @Override
    ZoneOffset getZoneOffset()
    {
        return value.getOffset();
    }

    @Override
    public boolean supportsTimeZone()
    {
        return true;
    }

    @Override
    public boolean equals( Value other )
    {
        // TODO: do we want equality to be this permissive?
        // This means that time("14:30+0100") = time("15:30+0200")
        return other instanceof TimeValue && value.isEqual( ((TimeValue) other).value );
    }

    @Override
    public <E extends Exception> void writeTo( ValueWriter<E> writer ) throws E
    {
        int offset = value.getOffset().getTotalSeconds();
        long seconds = value.getLong( ChronoField.SECOND_OF_DAY );
        seconds = ((-offset % SECONDS_PER_DAY) + seconds + SECONDS_PER_DAY) % SECONDS_PER_DAY;
        long nano = seconds * DurationValue.NANOS_PER_SECOND + value.getNano();
        writer.writeTime( nano, offset );
    }

    @Override
    public String prettyPrint()
    {
        return value.format( DateTimeFormatter.ISO_TIME );
    }

    @Override
    public ValueGroup valueGroup()
    {
        return ValueGroup.ZONED_TIME;
    }

    @Override
    protected int computeHash()
    {
        return Long.hashCode( value.toLocalTime().toNanoOfDay() - value.getOffset().getTotalSeconds() * 1000_000_000L );
    }

    @Override
    public <T> T map( ValueMapper<T> mapper )
    {
        return mapper.mapTime( this );
    }

    @Override
    public TimeValue add( DurationValue duration )
    {
        return replacement( value.plusNanos( duration.nanosOfDay() ) );
    }

    @Override
    public TimeValue sub( DurationValue duration )
    {
        return replacement( value.minusNanos( duration.nanosOfDay() ) );
    }

    @Override
    TimeValue replacement( OffsetTime time )
    {
        return time == value ? this : new TimeValue( time );
    }

    private static final String OFFSET_PATTERN = "(?<zone>Z|[+-](?<zoneHour>[0-9]{2})(?::?(?<zoneMinute>[0-9]{2}))?)";
    static final String TIME_PATTERN = LocalTimeValue.TIME_PATTERN + "(?:" + OFFSET_PATTERN + ")?";
    private static final Pattern PATTERN = Pattern.compile( "(?:T)?" + TIME_PATTERN );
    private static final Pattern OFFSET = Pattern.compile( OFFSET_PATTERN );

    static ZoneOffset parseOffset( String offset )
    {
        Matcher matcher = OFFSET.matcher( offset );
        if ( matcher.matches() )
        {
            return parseOffset( matcher );
        }
        throw new IllegalArgumentException( "Not a valid offset: " + offset );
    }

    static ZoneOffset parseOffset( Matcher matcher )
    {
        String zone = matcher.group( "zone" );
        if ( null == zone )
        {
            return null;
        }
        if ( "Z".equalsIgnoreCase( zone ) )
        {
            return UTC;
        }
        int factor = zone.charAt( 0 ) == '+' ? 1 : -1;
        int hours = parseInt( matcher.group( "zoneHour" ) );
        int minutes = optInt( matcher.group( "zoneMinute" ) );
        return ZoneOffset.ofHoursMinutes( factor * hours, factor * minutes );
    }

    private static TimeValue parse( Matcher matcher, Supplier<ZoneId> defaultZone )
    {
        return new TimeValue( OffsetTime
                .of( parseTime( matcher ), parseOffset( matcher, defaultZone ) ) );
    }

    private static ZoneOffset parseOffset( Matcher matcher, Supplier<ZoneId> defaultZone )
    {
        ZoneOffset offset = parseOffset( matcher );
        if ( offset == null )
        {
            ZoneId zoneId = defaultZone.get();
            offset = zoneId instanceof ZoneOffset ? (ZoneOffset) zoneId : zoneId.getRules().getOffset( Instant.now() );
        }
        return offset;
    }

    abstract static class TimeBuilder<Result> extends Builder<Result>
    {
        TimeBuilder( Supplier<ZoneId> defaultZone )
        {
            super( defaultZone );
        }

        @Override
        protected final boolean supportsDate()
        {
            return false;
        }

        @Override
        protected final boolean supportsTime()
        {
            return true;
        }

        @Override
        protected boolean supportsEpoch()
        {
            return false;
        }

        protected abstract Result selectTime( AnyValue time );
    }
}
