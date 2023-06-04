package rs117.hd.data.environments;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.utils.SunCalc;

public enum Diurnal {

    DEFAULT(false),
    DAY(true),
    NIGHT_MOON(true),
    NIGHT(true),
    SUNSET_SUNRISE(true),
    DUSK_DAWN(true);

    @Getter
    @RequiredArgsConstructor
    public enum Mode {
        ALWAYS_DAY("Always Day"),
        ALWAYS_NIGHT("Always Night"),
        ALWAYS_SUNSET("Always Sunset"), //remove later
        ALWAYS_DUSK("Always Post-Sunset"), //remove later
        CYCLE("1-hour Days");

        private final String name;

        @Override
        public String toString()
        {
            return name;
        }
    }

    private final boolean shadowsEnabled;
    private static final float SUNRISE_DURATION = 0.02f;
    private static final float DAWN_DURATION = 0.01333f;
    private static final long MS_PER_MINUTE = 60000;
    private static final float NIGHT_RANGE = 2.35f;

    Diurnal(boolean shadowsEnabled) {
        this.shadowsEnabled = shadowsEnabled;
    }

    public boolean isShadowsEnabled() {
        return shadowsEnabled;
    }

    public static Diurnal getTimeOfDay(double[] latLong, int dayLength) {
        Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

        double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
        double angleFromZenith = Math.abs(angles[1] - Math.PI / 2);
        boolean isNight = angleFromZenith > Math.PI / NIGHT_RANGE;
        boolean isDuskDawn = angleFromZenith > Math.PI / 2.45;
        boolean isSunriseSunset = angleFromZenith > Math.PI / 2.6;

        if (isNight) {
            angles = SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
            double moonAngleFromZenith = Math.abs(angles[1] - Math.PI / 2);

            return moonAngleFromZenith > Math.PI / 2.35 ? Diurnal.NIGHT : Diurnal.NIGHT_MOON;
        }
        else if (isDuskDawn) {
            return Diurnal.DUSK_DAWN;
        }
        else if (isSunriseSunset) {
            return Diurnal.SUNSET_SUNRISE;
        }
        else
            return Diurnal.DAY;
    }

    public static int getTransitionDuration(double[] latLong, int dayLength) {
        Diurnal timeOfDay = getTimeOfDay(latLong, dayLength);
        if(timeOfDay != null)
            switch (timeOfDay) {
                case DAY:
                case SUNSET_SUNRISE:
                    return (int)((MS_PER_MINUTE * dayLength) * SUNRISE_DURATION);
                case NIGHT:
                case DUSK_DAWN:
                    return (int)((MS_PER_MINUTE * dayLength) * DAWN_DURATION);
            }
        return (int)((MS_PER_MINUTE * dayLength) * SUNRISE_DURATION);
    }

    public static double[] getCurrentAngles(double[] latLong, int dayLength) {
        Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

        double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
        double angleFromZenith = Math.abs(angles[1] - Math.PI / 2);
        boolean isNight = angleFromZenith > Math.PI / NIGHT_RANGE;

        if (isNight) {
            // Switch to moon angles
            angles = SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
        }

        return angles;
    }

    public static Instant getModifiedDate(Instant currentDate, int dayLength) {
        long millisPerDay = dayLength * MS_PER_MINUTE;
        long elapsedMillis = currentDate.toEpochMilli() % millisPerDay;
        double speedFactor = 1440.0 / dayLength; // Speed factor to adjust the speed of time
        return currentDate.plusMillis((long) (elapsedMillis * speedFactor));
    }

    public static double[] getLatLong(WorldRegion currentRegion) {
        double latitude;
        double longitude;
        switch (currentRegion) {
            case AUSTRALIA:
                // Sydney
                latitude = -33.8478035;
                longitude = 150.6016588;
                break;
            case GERMANY:
                // Berlin
                latitude = 52.5063843;
                longitude = 13.0944281;
                break;
            case UNITED_STATES_OF_AMERICA:
                // Chicago
                latitude = 41.8337329;
                longitude = -87.7319639;
                break;
            default:
            case UNITED_KINGDOM:
                // Cambridge
                latitude = 52.1951;
                longitude = 0.1313;
                break;
        }
        return new double[] {latitude, longitude};
    }
}