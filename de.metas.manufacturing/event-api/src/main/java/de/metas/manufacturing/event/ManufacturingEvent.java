package de.metas.manufacturing.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/*
 * #%L
 * metasfresh-manufacturing-event-api
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * These are the high-level event pojos. We serialize and deserialize them and let them ride inside {@link de.metas.event.Event} instances.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(name = ShipmentScheduleEvent.TYPE, value = ShipmentScheduleEvent.class),
		@JsonSubTypes.Type(name = ReceiptScheduleEvent.TYPE, value = ReceiptScheduleEvent.class),
		@JsonSubTypes.Type(name = TransactionEvent.TYPE, value = TransactionEvent.class)
})
public interface ManufacturingEvent
{

}
