/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2017 azeti Networks AG (<info@azeti.net>)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package net.azeti.influxdb.pojomapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.influxdb.dto.QueryResult;

import net.azeti.influxdb.pojomapper.annotation.Column;
import net.azeti.influxdb.pojomapper.annotation.Measurement;
import net.azeti.influxdb.pojomapper.exception.InfluxDBMapperException;

/**
 * Main class responsible for mapping a QueryResult to POJO.
 * 
 * @author fmachado
 */
public class InfluxDBResultMapper {

	/**
	 * Data structure used to cache classes used as measurements.
	 */
	private static final ConcurrentMap<String, ConcurrentMap<String, Field>> CLASS_FIELD_CACHE = new ConcurrentHashMap<>();

	/**
	 * When a query is executed without {@link TimeUnit}, InfluxDB returns the <tt>time</tt>
	 * column as an ISO8601 date.
	 */
	private static final DateTimeFormatter ISO8601_FORMATTER = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
		.appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
		.appendPattern("X")
		.toFormatter();

	/**
	 * <p>
	 * Process a {@link QueryResult} object returned by the InfluxDB client inspecting the internal
	 * data structure and creating the respective object instances based on the Class passed as
	 * parameter.
	 * </p>
	 * 
	 * @param queryResult the InfluxDB result object
	 * @param clazz the Class that will be used to hold your measurement data
	 * @return a {@link List} of objects from the same Class passed as parameter and sorted on the
	 *         same order as received from InfluxDB.
	 * @throws InfluxDBMapperException If {@link QueryResult} parameter contain errors,
	 *         <tt>clazz</tt> parameter is not annotated with &#64;Measurement or it was not
	 *         possible to define the values of your POJO (e.g. due to an unsupported field type).
	 */
	public <T> List<T> toPOJO(QueryResult queryResult, Class<T> clazz) throws InfluxDBMapperException {
		throwExceptionIfMissingAnnotation(clazz);
		throwExceptionIfResultWithError(queryResult);
		cacheMeasurementClass(clazz);

		List<T> result = new LinkedList<T>();
		String measurementName = getMeasurementName(clazz);
		queryResult.getResults().stream()
			.forEach(singleResult -> {
				singleResult.getSeries().stream()
					.filter(series -> series.getName().equals(measurementName))
					.forEachOrdered(series -> {
						parseSeriesAs(series, clazz, result);
					});
			});

		return result;
	}

	void throwExceptionIfMissingAnnotation(Class<?> clazz) {
		if (!clazz.isAnnotationPresent(Measurement.class)) {
			throw new IllegalArgumentException("Class " + clazz.getName() + " is not annotated with @" + Measurement.class.getSimpleName());
		}
	}

	void throwExceptionIfResultWithError(QueryResult queryResult) {
		if (queryResult.getError() != null) {
			throw new InfluxDBMapperException("InfluxDB returned an error: " + queryResult.getError());
		}

		queryResult.getResults().forEach(seriesResult -> {
			if (seriesResult.getError() != null) {
				throw new InfluxDBMapperException("InfluxDB returned an error with Series: " + seriesResult.getError());
			}
		});
	}

	void cacheMeasurementClass(Class<?>... classVarAgrs) {
		for (Class<?> clazz : classVarAgrs) {
			if (CLASS_FIELD_CACHE.containsKey(clazz.getName())) {
				continue;
			}
			ConcurrentMap<String, Field> initialMap = new ConcurrentHashMap<>();
			ConcurrentMap<String, Field> influxColumnAndFieldMap = CLASS_FIELD_CACHE.putIfAbsent(clazz.getName(), initialMap);
			influxColumnAndFieldMap = (influxColumnAndFieldMap == null) ? initialMap : influxColumnAndFieldMap;

			for (Field field : clazz.getDeclaredFields()) {
				Column colAnnotation = field.getAnnotation(Column.class);
				if (colAnnotation != null) {
					influxColumnAndFieldMap.put(colAnnotation.name(), field);
				}
			}
		}
	}

	String getMeasurementName(Class<?> clazz) {
		return ((Measurement) clazz.getAnnotation(Measurement.class)).name();
	}

	<T> List<T> parseSeriesAs(QueryResult.Series series, Class<T> clazz, List<T> result) {
		int columnSize = series.getColumns().size();
		try {
			T object = null;
			for (List<Object> row : series.getValues()) {
				for (int i = 0; i < columnSize; i++) {
					String resultColumnName = series.getColumns().get(i);
					Field correspondingField = CLASS_FIELD_CACHE.get(clazz.getName()).get(resultColumnName);
					if (correspondingField != null) {
						if (object == null) {
							object = clazz.newInstance();
						}
						setFieldValue(object, correspondingField, row.get(i));
					}
				}
				if (object != null) {
					result.add(object);
					object = null;
				}
			}
		} catch (InstantiationException | IllegalAccessException e) {
			throw new InfluxDBMapperException(e);
		}
		return result;
	}

	/**
	 * InfluxDB client returns any number as Double.
	 * See https://github.com/influxdata/influxdb-java/issues/153#issuecomment-259681987
	 * for more information.
	 * 
	 * @param object
	 * @param field
	 * @param value
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 */
	<T> void setFieldValue(T object, Field field, Object value) throws IllegalArgumentException, IllegalAccessException {
		if (value == null) {
			return;
		}
		Class<?> fieldType = field.getType();
		boolean oldAccessibleState = field.isAccessible();
		try {
			field.setAccessible(true);
			if (fieldValueModified(fieldType, field, object, value)
				|| fieldValueForPrimitivesModified(fieldType, field, object, value)
				|| fieldValueForPrimitiveWrappersModified(fieldType, field, object, value)) {
				return;
			}
			String msg = "Class '%s' field '%s' is from an unsupported type '%s'.";
			throw new InfluxDBMapperException(String.format(msg, object.getClass().getName(), field.getName(), field.getType()));
		} catch (ClassCastException e) {
			String msg = "Class '%s' field '%s' was defined with a different field type and caused a ClassCastException. "
				+ "The correct type is '%s' (current field value: '%s').";
			throw new InfluxDBMapperException(
				String.format(msg, object.getClass().getName(), field.getName(), value.getClass().getName(), value));
		} finally {
			field.setAccessible(oldAccessibleState);
		}
	}

	<T> boolean fieldValueModified(Class<?> fieldType, Field field, T object, Object value) throws IllegalArgumentException, IllegalAccessException {
		if (String.class.isAssignableFrom(fieldType)) {
			field.set(object, String.valueOf(value));
			return true;
		}
		if (Instant.class.isAssignableFrom(fieldType)) {
			Instant instant;
			if (value instanceof String) {
				instant = Instant.from(ISO8601_FORMATTER.parse(String.valueOf(value)));
			} else if (value instanceof Long) {
				instant = Instant.ofEpochMilli((Long) value);
			} else if (value instanceof Double) {
				instant = Instant.ofEpochMilli(((Double) value).longValue());
			} else {
				throw new InfluxDBMapperException("Unsupported type " + field.getClass() + " for field " + field.getName());
			}
			field.set(object, instant);
			return true;
		}
		return false;
	}

	<T> boolean fieldValueForPrimitivesModified(Class<?> fieldType, Field field, T object, Object value)
		throws IllegalArgumentException, IllegalAccessException {
		if (double.class.isAssignableFrom(fieldType)) {
			field.setDouble(object, ((Double) value).doubleValue());
			return true;
		}
		if (long.class.isAssignableFrom(fieldType)) {
			field.setLong(object, ((Double) value).longValue());
			return true;
		}
		if (int.class.isAssignableFrom(fieldType)) {
			field.setInt(object, ((Double) value).intValue());
			return true;
		}
		if (boolean.class.isAssignableFrom(fieldType)) {
			field.setBoolean(object, Boolean.valueOf(String.valueOf(value)).booleanValue());
			return true;
		}
		return false;
	}

	<T> boolean fieldValueForPrimitiveWrappersModified(Class<?> fieldType, Field field, T object, Object value)
		throws IllegalArgumentException, IllegalAccessException {
		if (Double.class.isAssignableFrom(fieldType)) {
			field.set(object, value);
			return true;
		}
		if (Long.class.isAssignableFrom(fieldType)) {
			field.set(object, Long.valueOf(((Double) value).longValue()));
			return true;
		}
		if (Integer.class.isAssignableFrom(fieldType)) {
			field.set(object, Integer.valueOf(((Double) value).intValue()));
			return true;
		}
		if (Boolean.class.isAssignableFrom(fieldType)) {
			field.set(object, Boolean.valueOf(String.valueOf(value)));
			return true;
		}
		return false;
	}
}