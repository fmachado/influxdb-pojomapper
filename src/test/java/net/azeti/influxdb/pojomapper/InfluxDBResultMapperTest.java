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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.influxdb.dto.QueryResult;
import org.junit.Test;

import net.azeti.influxdb.pojomapper.annotation.Column;
import net.azeti.influxdb.pojomapper.annotation.Measurement;
import net.azeti.influxdb.pojomapper.exception.InfluxDBMapperException;

/**
 * @author fmachado
 */
public class InfluxDBResultMapperTest {

	InfluxDBResultMapper mapper = new InfluxDBResultMapper();

	@Test(expected = IllegalArgumentException.class)
	public void throwExceptionIfMissingAnnotation() {
		mapper.throwExceptionIfMissingAnnotation(String.class);
	}

	@Test(expected = InfluxDBMapperException.class)
	public void throwExceptionIfError_InfluxQueryResultHasError() {
		QueryResult queryResult = new QueryResult();
		queryResult.setError("main queryresult error");

		mapper.throwExceptionIfResultWithError(queryResult);
	}

	@Test(expected = InfluxDBMapperException.class)
	public void throwExceptionIfError_InfluxQueryResultSeriesHasError() {
		QueryResult queryResult = new QueryResult();

		QueryResult.Result seriesResult = new QueryResult.Result();
		seriesResult.setError("series error");

		queryResult.setResults(Arrays.asList(seriesResult));

		mapper.throwExceptionIfResultWithError(queryResult);
	}

	@Test
	public void testGetMeasurementName_testStateMeasurement() {
		assertEquals("CustomMeasurement", mapper.getMeasurementName(MyCustomMeasurement.class));
	}

	@Test
	public void testParseSeriesAs_testTwoValidSeries() {
		mapper.cacheMeasurementClass(MyCustomMeasurement.class);

		List<String> columnList = Arrays.asList("time", "uuid");

		List<Object> firstSeriesResult = Arrays.asList(Instant.now().toEpochMilli(), UUID.randomUUID().toString());
		List<Object> secondSeriesResult = Arrays.asList(Instant.now().plusSeconds(1).toEpochMilli(), UUID.randomUUID().toString());

		QueryResult.Series series = new QueryResult.Series();
		series.setColumns(columnList);
		series.setValues(Arrays.asList(firstSeriesResult, secondSeriesResult));

		List<MyCustomMeasurement> result = new LinkedList<>();
		mapper.parseSeriesAs(series, MyCustomMeasurement.class, result);

		assertTrue("there must be two series in the result list", result.size() == 2);

		assertEquals("Field 'time' (1st series) is not valid", firstSeriesResult.get(0), result.get(0).time.toEpochMilli());
		assertEquals("Field 'uuid' (1st series) is not valid", firstSeriesResult.get(1), result.get(0).uuid);

		assertEquals("Field 'time' (2nd series) is not valid", secondSeriesResult.get(0), result.get(1).time.toEpochMilli());
		assertEquals("Field 'uuid' (2nd series) is not valid", secondSeriesResult.get(1), result.get(1).uuid);
	}

	@Test
	public void testParseSeriesAs_testNonNullAndValidValues() {
		mapper.cacheMeasurementClass(MyCustomMeasurement.class);

		List<String> columnList = Arrays.asList("time", "uuid",
			"doubleObject", "longObject", "integerObject",
			"doublePrimitive", "longPrimitive", "integerPrimitive",
			"booleanObject", "booleanPrimitive");

		// InfluxDB client returns the time representation as Double.
		Double now = Long.valueOf(System.currentTimeMillis()).doubleValue();
		String uuidAsString = UUID.randomUUID().toString();

		// InfluxDB client returns any number as Double.
		// See https://github.com/influxdata/influxdb-java/issues/153#issuecomment-259681987
		// for more information.
		List<Object> seriesResult = Arrays.asList(now, uuidAsString,
			new Double("1.01"), new Double("2"), new Double("3"),
			new Double("1.01"), new Double("4"), new Double("5"),
			"false", "true");

		QueryResult.Series series = new QueryResult.Series();
		series.setColumns(columnList);
		series.setValues(Arrays.asList(seriesResult));

		List<MyCustomMeasurement> result = new LinkedList<>();
		mapper.parseSeriesAs(series, MyCustomMeasurement.class, result);

		MyCustomMeasurement myObject = result.get(0);
		assertEquals("field 'time' does not match", now.longValue(), myObject.time.toEpochMilli());
		assertEquals("field 'uuid' does not match", uuidAsString, myObject.uuid);

		assertEquals("field 'doubleObject' does not match", asDouble(seriesResult.get(2)), myObject.doubleObject);
		assertEquals("field 'longObject' does not match", new Long(asDouble(seriesResult.get(3)).longValue()), myObject.longObject);
		assertEquals("field 'integerObject' does not match", new Integer(asDouble(seriesResult.get(4)).intValue()), myObject.integerObject);

		assertTrue("field 'doublePrimitive' does not match",
			Double.compare(asDouble(seriesResult.get(5)).doubleValue(), myObject.doublePrimitive) == 0);

		assertTrue("field 'longPrimitive' does not match",
			Long.compare(asDouble(seriesResult.get(6)).longValue(), myObject.longPrimitive) == 0);

		assertTrue("field 'integerPrimitive' does not match",
			Integer.compare(asDouble(seriesResult.get(7)).intValue(), myObject.integerPrimitive) == 0);

		assertEquals("booleanObject 'time' does not match",
			Boolean.valueOf(String.valueOf(seriesResult.get(8))), myObject.booleanObject);

		assertEquals("booleanPrimitive 'uuid' does not match",
			Boolean.valueOf(String.valueOf(seriesResult.get(9))).booleanValue(), myObject.booleanPrimitive);
	}

	Double asDouble(Object obj) {
		return (Double) obj;
	}

	@Test
	public void testFieldValueModified_DateAsISO8601() {
		mapper.cacheMeasurementClass(MyCustomMeasurement.class);

		List<String> columnList = Arrays.asList("time");
		List<Object> firstSeriesResult = Arrays.asList("2017-06-19T09:29:45.655123Z");

		QueryResult.Series series = new QueryResult.Series();
		series.setColumns(columnList);
		series.setValues(Arrays.asList(firstSeriesResult));

		List<MyCustomMeasurement> result = new LinkedList<>();
		mapper.parseSeriesAs(series, MyCustomMeasurement.class, result);

		assertTrue(result.size() == 1);
	}

	@Test(expected = InfluxDBMapperException.class)
	public void testUnsupportedField() {
		mapper.cacheMeasurementClass(MyPojoWithUnsupportedField.class);

		List<String> columnList = Arrays.asList("bar");
		List<Object> firstSeriesResult = Arrays.asList("content representing a Date");

		QueryResult.Series series = new QueryResult.Series();
		series.setColumns(columnList);
		series.setValues(Arrays.asList(firstSeriesResult));

		List<MyPojoWithUnsupportedField> result = new LinkedList<>();
		mapper.parseSeriesAs(series, MyPojoWithUnsupportedField.class, result);
	}

	@Measurement(name = "CustomMeasurement")
	static class MyCustomMeasurement {

		@Column(name = "time")
		private Instant time;

		@Column(name = "uuid")
		private String uuid;

		@Column(name = "doubleObject")
		private Double doubleObject;

		@Column(name = "longObject")
		private Long longObject;

		@Column(name = "integerObject")
		private Integer integerObject;

		@Column(name = "doublePrimitive")
		private double doublePrimitive;

		@Column(name = "longPrimitive")
		private long longPrimitive;

		@Column(name = "integerPrimitive")
		private int integerPrimitive;

		@Column(name = "booleanObject")
		private Boolean booleanObject;

		@Column(name = "booleanPrimitive")
		private boolean booleanPrimitive;

		@SuppressWarnings("unused")
		private String nonColumn1;

		@SuppressWarnings("unused")
		private Random rnd;

		@Override
		public String toString() {
			return "MyCustomMeasurement [time=" + time + ", uuid=" + uuid + ", doubleObject=" + doubleObject + ", longObject=" + longObject
				+ ", integerObject=" + integerObject + ", doublePrimitive=" + doublePrimitive + ", longPrimitive=" + longPrimitive
				+ ", integerPrimitive=" + integerPrimitive + ", booleanObject=" + booleanObject + ", booleanPrimitive=" + booleanPrimitive + "]";
		}
	}

	@Measurement(name = "foo")
	static class MyPojoWithUnsupportedField {

		@Column(name = "bar")
		private Date myDate;
	}
}