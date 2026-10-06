package eu.explorerseden.nicemerl;

import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * Weather forecast, moon phase and the date, read from the overworld's weather timers and clock. When Nice Actions'
 * calendar is installed (storage eden:calendar), times are given as its dates ("Tuesday, March 4, Year 2, 14:00");
 * otherwise as the game's day number and clock.
 */
final class MerlForecast {
	private static final TextColor PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor VALUE = TextColor.fromRgb(0xFFD966);
	private static final int DAY = 24000;
	private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
	private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
			"September", "October", "November", "December"};
	private static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
	private static final String[] MOONS = {"Full moon 🌕", "Waning gibbous 🌖", "Third quarter 🌗", "Waning crescent 🌘",
			"New moon 🌑", "Waxing crescent 🌒", "First quarter 🌓", "Waxing gibbous 🌔"};

	private MerlForecast() {}

	static Component answer(MinecraftServer server, String kind, String user) {
		ServerLevel level = server.overworld();
		Clock clock = Clock.of(server, level);
		return switch (kind) {
			case "moon" -> moon(clock, user);
			case "date" -> date(clock, user);
			default -> weather(server, level, clock, user);
		};
	}

	private static Component weather(MinecraftServer server, ServerLevel level, Clock clock, String user) {
		WeatherData data = level.getWeatherData();
		boolean raining = data.isRaining(), thundering = data.isThundering();
		String now = raining && thundering ? "Thunderstorm ⛈" : raining ? "Rain 🌧" : clock.isDay() ? "Clear ☀" : "Clear ⭐";
		if (!server.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
			return Component.literal(MerlLines.pick("forecast_frozen", "user", user))
					.append(row("Now", now)).append(row("Weather cycle", "off (it stays like this)"));
		}
		MutableComponent out = Component.literal(MerlLines.pick(raining ? "forecast_rainy" : "forecast_clear", "user", user))
				.append(row("Now", now));
		int clear = data.getClearWeatherTime(), rain = data.getRainTime(), thunder = data.getThunderTime();
		if (clear > 0) {
			// Set by /weather clear: the rain and thunder timers start over (randomly) afterwards.
			out.append(row("Clear until", clock.when(clear)));
			out.append(row("After that", "up to chance"));
		} else if (raining && thundering) {
			if (thunder < rain) {
				out.append(row("Thunder stops", clock.when(thunder)));
				out.append(row("Rain stops", clock.when(rain)));
			} else {
				out.append(row("Storm ends", clock.when(rain)));
			}
		} else if (raining) {
			out.append(row("Rain stops", clock.when(rain)));
			if (!thundering && thunder < rain) out.append(row("Thunder starts", clock.when(thunder)));
		} else {
			out.append(row("Rain starts", clock.when(rain)));
			if (thundering && thunder > rain) out.append(row("Kind", "a thunderstorm ⛈"));
			else if (!thundering && thunder >= rain) out.append(row("Thunder possible", clock.when(thunder)));
		}
		if (clock.rate() <= 0) out.append(row("Day and night", "paused"));
		return out;
	}

	private static Component moon(Clock clock, String user) {
		long day = clock.time() / DAY;
		int phase = (int) Math.floorMod(day, (long) MoonPhase.COUNT);
		boolean night = !clock.isDay();
		MutableComponent out = Component.literal(MerlLines.pick("forecast_moon", "user", user))
				.append(row(night ? "Tonight" : "Tonight's moon", MOONS[phase]));
		int untilFull = Math.floorMod(-phase, MoonPhase.COUNT);
		if (untilFull == 0) {
			out.append(row("Full moon", night ? "right now!" : "tonight!"));
		} else {
			// The moon changes at sunrise; the full moon is seen on the night of that day (at 19:00 game time).
			long fullNight = (day + untilFull) * DAY + 13000;
			out.append(row("Next full moon", untilFull + (untilFull == 1 ? " night" : " nights") + clock.at(fullNight - clock.time())));
		}
		int untilNew = Math.floorMod(4 - phase, MoonPhase.COUNT);
		if (untilNew > 0) out.append(row("Next new moon", untilNew + (untilNew == 1 ? " night" : " nights")));
		out.append(Component.literal("\n(" + MerlLines.pick("forecast_moon_note") + ")").withStyle(ChatFormatting.GRAY));
		return out;
	}

	private static Component date(Clock clock, String user) {
		MutableComponent out = Component.literal(MerlLines.pick("forecast_date", "user", user));
		Calendar calendar = clock.calendar();
		if (calendar != null) {
			out.append(row("Date", calendar.weekday() + ", " + MONTHS[calendar.month() - 1] + " " + calendar.day() + ", Year " + calendar.year()));
			out.append(row("Season", season(calendar.month())));
		} else {
			out.append(row("Day", String.valueOf(clock.time() / DAY + 1)));
		}
		out.append(row("Time", clock.clockText(clock.time()) + (clock.isDay() ? " ☀" : " ⭐")));
		if (clock.rate() > 0 && clock.isDay()) out.append(row("Sunset", clock.when(clock.ticksUntil(12000))));
		else if (clock.rate() > 0) out.append(row("Sunrise", clock.when(clock.ticksUntil(0))));
		return out;
	}

	private static String season(int month) {
		return month <= 2 || month == 12 ? "Winter ❄" : month <= 5 ? "Spring 🌸" : month <= 8 ? "Summer ☀" : "Autumn 🍂";
	}

	private static Component row(String label, String value) {
		return Component.literal("\n ☁ ").withStyle(Style.EMPTY.withColor(PINK))
				.append(Component.literal(label + ": ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(value).withStyle(Style.EMPTY.withColor(VALUE)));
	}

	/** "4 minutes", "1 hour 20 minutes" in real time. */
	static String duration(long ticks) {
		long seconds = Math.max(0, ticks / 20);
		if (seconds < 60) return "less than a minute";
		long minutes = Math.round(seconds / 60.0);
		if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
		long hours = minutes / 60, rest = minutes % 60;
		return hours + (hours == 1 ? " hour" : " hours") + (rest > 0 ? " " + rest + (rest == 1 ? " minute" : " minutes") : "");
	}

	/** A date from Nice Actions' calendar; its day turns over at midnight (clock time 18000). */
	record Calendar(int day, int month, int year, String weekday, boolean twelveHour) {
		Calendar plusDays(int days) {
			int d = day, m = month, y = year;
			int w = indexOf(weekday);
			for (int i = 0; i < days; i++) {
				if (++d > MONTH_DAYS[m - 1]) {
					d = 1;
					if (++m > 12) {
						m = 1;
						y++;
					}
				}
			}
			return new Calendar(d, m, y, w < 0 ? weekday : WEEKDAYS[(w + days) % 7], twelveHour);
		}

		private static int indexOf(String weekday) {
			for (int i = 0; i < WEEKDAYS.length; i++) if (WEEKDAYS[i].equalsIgnoreCase(weekday)) return i;
			return -1;
		}
	}

	/** The overworld clock: its time, speed (a slower day makes in-game times further away) and the calendar. */
	record Clock(long time, float rate, Calendar calendar) {
		static Clock of(MinecraftServer server, ServerLevel level) {
			long time = level.getOverworldClockTime();
			float rate = 1;
			Optional<Holder.Reference<WorldClock>> holder = level.registryAccess().get(WorldClocks.OVERWORLD);
			if (holder.isPresent()) {
				ClockInstance instance = level.clockManager().getInstance(holder.get());
				rate = instance.isPaused() ? 0 : instance.rate();
			}
			if (!server.getGameRules().get(GameRules.ADVANCE_TIME)) rate = 0;
			return new Clock(time, rate, readCalendar(server));
		}

		private static Calendar readCalendar(MinecraftServer server) {
			Identifier id = Identifier.tryParse("eden:calendar");
			if (id == null) return null;
			Optional<CompoundTag> global = server.getCommandStorage().get(id).getCompound("global");
			if (global.isEmpty() || global.get().getInt("month").isEmpty()) return null;
			CompoundTag tag = global.get();
			int month = Math.clamp(tag.getIntOr("month", 1), 1, 12);
			Optional<CompoundTag> settings = server.getCommandStorage().get(Identifier.parse("eden:settings")).getCompound("nice_actions");
			boolean twelve = settings.map(s -> s.getIntOr("time_format", 24) == 12).orElse(false);
			return new Calendar(Math.max(1, tag.getIntOr("day", 1)), month, tag.getIntOr("year", 1), tag.getStringOr("weekday", "Monday"), twelve);
		}

		boolean isDay() {
			long t = Math.floorMod(time, (long) DAY);
			return t < 12000 || t >= 23000;
		}

		/** Clock ticks until the given time of day comes round (0 = sunrise, 12000 = sunset). */
		long ticksUntil(int dayTime) {
			long clockTicks = Math.floorMod(dayTime - time, (long) DAY);
			return rate <= 0 ? 0 : (long) (clockTicks / rate);
		}

		/** "in 4 minutes · Tuesday, March 4, 14:00" for something that happens in so many server ticks. */
		String when(long ticks) {
			return "in " + duration(ticks) + (rate > 0 ? at((long) (ticks * rate)) : "");
		}

		/** " · Tuesday, March 4, 14:00" (or " · day 213, 14:00") for a point so many clock ticks from now. */
		String at(long clockTicks) {
			long then = time + clockTicks;
			if (calendar == null) return " · day " + (then / DAY + 1) + ", " + clockText(then);
			// Nice Actions' day turns over at midnight, which is clock time 18000.
			int days = (int) (Math.floorDiv(then - 18000, (long) DAY) - Math.floorDiv(time - 18000, (long) DAY));
			Calendar date = calendar.plusDays(Math.max(0, days));
			String day = days == 0 ? "today" : days == 1 ? "tomorrow" : date.weekday() + ", " + MONTHS[date.month() - 1] + " " + date.day();
			return " · " + day + ", " + clockText(then);
		}

		/** The time of day like Nice Actions shows it: clock 0 is 06:00, 1000 clock ticks are an hour. */
		String clockText(long clockTime) {
			long minutes = Math.floorMod(clockTime, (long) DAY) * 3 / 50;
			int hour = (int) ((minutes / 60 + 6) % 24), minute = (int) (minutes % 60);
			if (calendar != null && calendar.twelveHour()) {
				int h = hour % 12 == 0 ? 12 : hour % 12;
				return h + ":" + (minute < 10 ? "0" : "") + minute + (hour < 12 ? " AM" : " PM");
			}
			return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
		}
	}
}
