package de.metas.manufacturing.dispo;

import java.util.Date;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-manufacturing-dispo
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
 * Identifies a set of candidates.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@Builder
@Data
public class CandidatesSegment
{
	@NonNull
	private final Integer productId;

	@NonNull
	private final Integer warehouseId;

	/**
	 * The locator within a warehouse might be unspecified.
	 */
	private final Integer locatorId;

	@NonNull
	private final Date projectedDate;

	public int getLocatorIdNotNull()
	{
		return locatorId == null ? 0 : locatorId;
	}
}
