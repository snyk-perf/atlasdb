/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.jdbc.config;

import javax.sql.DataSource;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.google.auto.service.AutoService;

import io.dropwizard.jackson.Discoverable;

@AutoService(Discoverable.class)
@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type", visible = false)
public interface JdbcDataSourceConfiguration {

    String getType();
    String getSqlDialect();

    DataSource createDataSource();
}
