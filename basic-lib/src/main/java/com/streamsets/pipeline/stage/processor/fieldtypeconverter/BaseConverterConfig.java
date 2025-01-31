/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.fieldtypeconverter;

import com.google.common.base.Preconditions;
import com.streamsets.pipeline.ZonedDateTimeFormatChooserValues;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.config.CharsetChooserValues;
import com.streamsets.pipeline.config.DateFormat;
import com.streamsets.pipeline.config.DecimalScaleRoundingStrategy;
import com.streamsets.pipeline.config.DecimalScaleRoundingStrategyChooserValues;
import com.streamsets.pipeline.config.LocaleChooserValues;
import com.streamsets.pipeline.config.DateFormatChooserValues;
import com.streamsets.pipeline.config.PrimitiveFieldTypeChooserValues;
import com.streamsets.pipeline.config.TimeZoneChooserValues;
import com.streamsets.pipeline.config.ZonedDateTimeFormat;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Base configuration for target type
 */
public class BaseConverterConfig {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue="INTEGER",
      label = "Convert to Type",
      description = "Select a compatible data type",
      displayMode = ConfigDef.DisplayMode.BASIC,
      displayPosition = 20
  )
  @ValueChooserModel(PrimitiveFieldTypeChooserValues.class)
  public Field.Type targetType;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue="ERROR",
      label = "Source Field is Empty",
      description = "Action to take when the source field is an empty string.",
      displayPosition = 20,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {
          "BYTE", "CHAR", "DATE", "DATETIME", "TIME", "ZONED_DATETIME",
          "DECIMAL", "DOUBLE", "FLOAT", "INTEGER", "LONG", "SHORT"
      }
  )
  @ValueChooserModel(InputFieldEmptyChooserValues.class)
  public InputFieldEmpty inputFieldEmpty = InputFieldEmpty.ERROR;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Treat Input Field as Date",
      description = "Select to convert input Long to DateTime before converting to a String",
      displayPosition = 20,
      displayMode = ConfigDef.DisplayMode.ADVANCED,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = "STRING"
  )
  public boolean treatInputFieldAsDate;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "en,US",
      label = "Data Locale",
      description = "Affects the interpretation of locale sensitive data, such as using the comma as a decimal " +
                    "separator",
      displayPosition = 30,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {"BYTE", "INTEGER", "LONG", "DOUBLE", "DECIMAL", "FLOAT", "SHORT", "ZONED_DATETIME"}
  )
  @ValueChooserModel(LocaleChooserValues.class)
  public String dataLocale;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "-1",
      label = "Scale",
      description = "Decimal Value Scale",
      displayPosition = 40,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {"DECIMAL"}
  )
  public int scale;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.MODEL,
      defaultValue = "ROUND_UNNECESSARY",
      label = "Rounding Strategy",
      description = "Rounding strategy during scale conversion",
      displayPosition = 50,
      displayMode = ConfigDef.DisplayMode.ADVANCED,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {"DECIMAL"}
  )
  @ValueChooserModel(DecimalScaleRoundingStrategyChooserValues.class)
  public DecimalScaleRoundingStrategy decimalScaleRoundingStrategy;

  private Locale locale;

  public Locale getLocale() {
    if (locale == null) {
      locale = LocaleChooserValues.getLocale(dataLocale);
    }
    return locale;
  }

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue="YYYY_MM_DD",
      label = "Date Format",
      description="Select or enter any valid date or datetime format",
      displayPosition = 40,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {"DATE", "DATETIME", "TIME", "STRING"}
  )
  @ValueChooserModel(DateFormatChooserValues.class)
  public DateFormat dateFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Other Date Format",
      displayPosition = 50,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "dateFormat",
      group = "TYPE_CONVERSION",
      triggeredByValue = "OTHER"
  )
  public String otherDateFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue="ISO_ZONED_DATE_TIME",
      label = "Zoned DateTime Format",
      description="Select or enter any valid date or datetime format",
      displayPosition = 60,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = {"ZONED_DATETIME", "STRING"}
  )
  @ValueChooserModel(ZonedDateTimeFormatChooserValues.class)
  public ZonedDateTimeFormat zonedDateTimeFormat = ZonedDateTimeFormat.ISO_ZONED_DATE_TIME;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Target Time Zone",
      description = "Time Zone ",
      displayPosition = 180,
      displayMode = ConfigDef.DisplayMode.BASIC,
      group = "TYPE_CONVERSION",
      dependsOn = "targetType",
      triggeredByValue = {"ZONED_DATETIME"}
  )
  @ValueChooserModel(TimeZoneChooserValues.class)
  public String zonedDateTimeTargetTimeZone = ZoneId.systemDefault().getId();

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Other Zoned DateTime Format",
      displayPosition = 70,
      displayMode = ConfigDef.DisplayMode.BASIC,
      dependsOn = "zonedDateTimeFormat",
      group = "TYPE_CONVERSION",
      triggeredByValue = "OTHER"
  )
  public String otherZonedDateTimeFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "UTF-8",
      label = "CharSet",
      displayPosition = 80,
      displayMode = ConfigDef.DisplayMode.ADVANCED,
      dependsOn = "targetType",
      group = "TYPE_CONVERSION",
      triggeredByValue = "STRING"
  )
  @ValueChooserModel(CharsetChooserValues.class)
  public String encoding = "UTF-8";

  private DateTimeFormatter dateTimeFormatter;
  /**
   * Return configured date mask.
   */
  public String getDateMask() {
    return dateFormat != DateFormat.OTHER ? dateFormat.getFormat() : otherDateFormat;
  }

  public DateTimeFormatter getFormatter() {
    if (!targetType.isOneOf(Field.Type.STRING, Field.Type.ZONED_DATETIME)) {
      return null;
    }
    return zonedDateTimeFormat.getFormatter().orElseGet(() -> {
      Preconditions.checkNotNull(otherZonedDateTimeFormat,
          "Zoned Datetime format cannot be null");
      if (dateTimeFormatter == null) {
        dateTimeFormatter = DateTimeFormatter.ofPattern(otherZonedDateTimeFormat, getLocale());
      }
      return dateTimeFormatter;
    });
  }

}
