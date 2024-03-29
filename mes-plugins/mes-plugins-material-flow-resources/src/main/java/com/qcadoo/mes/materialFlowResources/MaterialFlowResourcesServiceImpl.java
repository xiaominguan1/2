/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.materialFlowResources;

import static com.qcadoo.mes.basic.constants.ProductFields.UNIT;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import com.qcadoo.mes.materialFlowResources.constants.StorageLocationsForProductDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.google.common.collect.Maps;
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.basic.util.CurrencyService;
import com.qcadoo.mes.materialFlow.constants.MaterialFlowConstants;
import com.qcadoo.mes.materialFlowResources.constants.MaterialFlowResourcesConstants;
import com.qcadoo.mes.materialFlowResources.constants.ResourceFields;
import com.qcadoo.mes.materialFlowResources.constants.ResourceStockDtoFields;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchQueryBuilder;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FieldComponent;

@Service
public class MaterialFlowResourcesServiceImpl implements MaterialFlowResourcesService {

    private static final String L_PRICE_CURRENCY = "priceCurrency";

    private static final String L_QUANTITY_UNIT = "quantityUNIT";

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberService numberService;

    @Autowired
    private CurrencyService currencyService;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<Entity> getWarehouseLocationsFromDB() {
        return getLocationDD().find().list().getEntities();
    }

    @Override
    public BigDecimal getResourcesQuantityForLocationAndProduct(final Entity location, final Entity product) {
        List<Entity> resources = getResourcesForLocationAndProduct(location, product);

        if (Objects.isNull(resources)) {
            return null;
        } else {
            BigDecimal resourcesQuantity = BigDecimal.ZERO;

            for (Entity resource : resources) {
                resourcesQuantity = resourcesQuantity.add(resource.getDecimalField(ResourceFields.QUANTITY),
                        numberService.getMathContext());
            }

            return resourcesQuantity;
        }
    }

    @Override
    public List<Entity> getResourcesForLocationAndProduct(final Entity location, final Entity product) {
        return getResourceDD().find().add(SearchRestrictions.belongsTo(ResourceFields.LOCATION, location))
                .add(SearchRestrictions.belongsTo(ResourceFields.PRODUCT, product))
                .addOrder(SearchOrders.asc(ResourceFields.TIME)).list().getEntities();
    }

    @Override
    public Map<Long, BigDecimal> getQuantitiesForProductsAndLocation(final List<Entity> products, final Entity location) {
        return getQuantitiesForProductsAndLocation(products, location, false);
    }

    @Override
    public Map<Long, BigDecimal> getQuantitiesForProductsAndLocation(final List<Entity> products, final Entity location,
            final boolean withoutBlockedForQualityControl) {
        return getQuantitiesForProductsAndLocation(products, location, withoutBlockedForQualityControl,
                ResourceStockDtoFields.AVAILABLE_QUANTITY);
    }

    @Override
    public Map<Long, BigDecimal> getQuantitiesForProductsAndLocation(final List<Entity> products, final Entity location,
            final boolean withoutBlockedForQualityControl, final String fieldName) {
        Map<Long, BigDecimal> quantities = Maps.newHashMap();

        if (products.size() > 0) {
            List<Integer> productIds = products.stream().map(product -> product.getId().intValue()).collect(Collectors.toList());
            Integer locationId = location.getId().intValue();

            StringBuilder query = new StringBuilder();

            query.append("SELECT ");
            query.append(
                    "resourceStockDto.product_id AS product_id, resourceStockDto.quantity AS quantity, resourceStockDto.availableQuantity AS availableQuantity ");
            query.append("FROM #materialFlowResources_resourceStockDto resourceStockDto ");
            query.append("WHERE resourceStockDto.product_id IN (:productIds) ");
            query.append("AND resourceStockDto.location_id = :locationId ");

            if (withoutBlockedForQualityControl) {
                query.append("AND resourceStockDto.blockedForQualityControl = false ");
            }

            SearchQueryBuilder searchQueryBuilder = getResourceStockDtoDD().find(query.toString());

            searchQueryBuilder.setParameterList("productIds", productIds);
            searchQueryBuilder.setParameter("locationId", locationId);

            List<Entity> resourceStocks = searchQueryBuilder.list().getEntities();

            resourceStocks.forEach(resourceStock -> quantities.put(
                    Long.valueOf(resourceStock.getIntegerField(ResourceStockDtoFields.PRODUCT_ID).intValue()),
                    ResourceStockDtoFields.AVAILABLE_QUANTITY.equals(fieldName)
                            ? resourceStock.getDecimalField(ResourceStockDtoFields.AVAILABLE_QUANTITY)
                            : resourceStock.getDecimalField(ResourceStockDtoFields.QUANTITY)));
        }

        return quantities;
    }

    @Override
    public Map<Long, Map<Long, BigDecimal>> getQuantitiesForProductsAndLocations(final List<Entity> products,
            final List<Entity> locations) {
        Map<Long, Map<Long, BigDecimal>> quantities = Maps.newHashMap();

        for (Entity location : locations) {
            quantities.put(location.getId(), getQuantitiesForProductsAndLocation(products, location));
        }

        return quantities;
    }

    public void fillUnitFieldValues(final ViewDefinitionState view) {
        Long productId = (Long) view.getComponentByReference(ResourceFields.PRODUCT).getFieldValue();

        if (Objects.isNull(productId)) {
            return;
        }

        Entity product = getProductDD().get(productId);
        String unit = product.getStringField(UNIT);

        FieldComponent unitField = (FieldComponent) view.getComponentByReference(L_QUANTITY_UNIT);
        unitField.setFieldValue(unit);
        unitField.requestComponentUpdateState();
    }

    public void fillCurrencyFieldValues(final ViewDefinitionState view) {
        String currency = currencyService.getCurrencyAlphabeticCode();

        FieldComponent currencyField = (FieldComponent) view.getComponentByReference(L_PRICE_CURRENCY);
        currencyField.setFieldValue(currency);
        currencyField.requestComponentUpdateState();
    }

    @Override
    public List<QuantityDto> getQuantitiesForProductsAndLocationWMS(final List<String> productNumbers, final Long materialFlowLocationId) {
        List<QuantityDto> quantityDtoList = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();

        if (!productNumbers.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT ");
            prepareQuery.append("storageLocationDto.productnumber as productNumber, storageLocationDto.resourcequantity as quantity, storageLocationDto.quantityinadditionalunit as additionalQuantity ");
            prepareQuery.append("FROM materialFlowResources_storageLocationDto as storageLocationDto ");
            prepareQuery.append("WHERE storageLocationDto.productnumber IN (:productNumbers) ");
            prepareQuery.append("AND storageLocationDto.location_id = :materialFlowLocationId ");

            params.put("productNumbers", productNumbers);
            params.put("materialFlowLocationId", materialFlowLocationId.intValue());

            quantityDtoList = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(QuantityDto.class));


            return quantityDtoList;
        }
        return quantityDtoList;
    }

    @Override
    public List<ResourcesQuantityDto> getResourceQuantities(final Long storageLocationId, final String productNumber) {
        List<ResourcesQuantityDto> resourcesQuantityDtoList = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();

        if (storageLocationId != null && !productNumber.isEmpty() && productNumber != null) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT DISTINCT ");
            prepareQuery.append("resourcedto.number as resourceNumber, ");
            prepareQuery.append("resourcedto.quantity as quantity, ");
            prepareQuery.append("resourcedto.quantityinadditionalunit as additionalQuantity, ");
            prepareQuery.append("internal.productunit as productUnit, ");
            prepareQuery.append("internal.productadditionalunit as productAdditionalUnit ");
            prepareQuery.append("FROM materialflowresources_resourcedto as resourcedto ");
            prepareQuery.append("JOIN materialflowresources_storagelocationdto_internal as internal ");
            prepareQuery.append("ON resourcedto.productnumber = internal.productnumber ");
            prepareQuery.append("WHERE resourcedto.productnumber = :productNumber ");
            prepareQuery.append("AND resourcedto.location_id = :storageLocationId");

            params.put("storageLocationId", storageLocationId);
            params.put("productNumber", productNumber);

            resourcesQuantityDtoList = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(ResourcesQuantityDto.class));

            return resourcesQuantityDtoList;
        }
        return resourcesQuantityDtoList;
    }

    public List<ResourceDetailsDto> getResourceDetails(final String resourceNumber) {
        List<ResourceDetailsDto> resourceDetailsDto = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();


        if(resourceNumber != null && !resourceNumber.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT ");
            prepareQuery.append("resourcedto.palletnumber as palletNumber, ");
            prepareQuery.append("resourcedto.batchnumber as batchNumber, ");
            prepareQuery.append("resourcedto.productiondate as productionDate, ");
            prepareQuery.append("resourcedto.expirationdate as expirationDate ");
            prepareQuery.append("FROM materialflowresources_resourcedto as resourcedto ");
            prepareQuery.append("WHERE resourcedto.number = :resourceNumber");

            params.put("resourceNumber", resourceNumber);

            resourceDetailsDto = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(ResourceDetailsDto.class));
            return resourceDetailsDto;
        }
        return resourceDetailsDto;
    }

    @Override
    public List<PalletNumberProductDTO> getProductsForPalletNumber(String palletNumber, List<String> userLocationNumbers) {
        List<PalletNumberProductDTO> palletNumberProductDTOList = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();

        if(palletNumber != null && !palletNumber.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT ");
            prepareQuery.append("palletstoragedto.storageLocationNumber as storageLocationNumber, ");
            prepareQuery.append("palletstoragedto.locationNumber as locationNumber, ");
            prepareQuery.append("resourcestockdto.product_id as productId, ");
            prepareQuery.append("resourcestockdto.productNumber as productNumber, ");
            prepareQuery.append("resourcestockdto.productName as productName, ");
            prepareQuery.append("resourcestockdto.productUnit as productUnit, ");
            prepareQuery.append("storagelocationdto.productAdditionalUnit as productAdditionalUnit, ");
            prepareQuery.append("resourcestockdto.quantity as quantity, ");
            prepareQuery.append("resourcestockdto.quantityInAdditionalUnit as quantityInAdditionalUnit, ");
            prepareQuery.append("resourcestockdto.location_id as locationId ");
            prepareQuery.append("from materialflowresources_resourcestockdto as resourcestockdto ");
            prepareQuery.append("join materialflowresources_palletstoragestatedto as palletstoragedto ");
            prepareQuery.append("on palletstoragedto.location_id = resourcestockdto.location_id ");
            prepareQuery.append("join materialflowresources_storagelocationdto as storagelocationdto ");
            prepareQuery.append("on palletstoragedto.location_id = storagelocationdto.location_id ");
            prepareQuery.append("where palletstoragedto.palletnumber = :palletNumber ");
            prepareQuery.append("and resourcestockdto.locationNumber in (:userLocationNumbers)");

            params.put("palletNumber", palletNumber);
            params.put("userLocationNumbers", userLocationNumbers);

            palletNumberProductDTOList = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(PalletNumberProductDTO.class));
            return palletNumberProductDTOList;
        }
        return palletNumberProductDTOList;
    }

    @Override
    public List<SumOfProductsDto> getSumOfProducts(String productNumber, List<String> locationNumbers) {
        List<SumOfProductsDto> list = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();

        if(productNumber != null && !productNumber.isEmpty() && !locationNumbers.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT ");
            prepareQuery.append("SUM (mfr.quantity) as quantitySum, ");
            prepareQuery.append("SUM (mfr.quantityInAdditionalUnit) as additionalQuantitySum ");
            prepareQuery.append("FROM materialflowresources_resourcedto as mfr ");
            prepareQuery.append("WHERE mfr.productnumber = :productNumber ");
            prepareQuery.append("AND mfr.locationNumber IN (:locationNumbers)");

            params.put("productNumber", productNumber);
            params.put("locationNumbers", locationNumbers);

            list = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(SumOfProductsDto.class));
            return list;
        }
        return list;
    }

    @Override
    public List<StorageLocationsForProductDto> getStoragesForProductNumber(String productNumber, List<String> locationNumbers) {
        List<StorageLocationsForProductDto> list = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();

        if(productNumber != null && !productNumber.isEmpty() && !locationNumbers.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT DISTINCT ");
            prepareQuery.append("mfrs.location_id as locationId, ");
            prepareQuery.append("mfrs.locationNumber as locationNumber, ");
            prepareQuery.append("mfrs.locationName as locationName, ");
            prepareQuery.append("mfrs.product_id as productId, ");
            prepareQuery.append("mfrs.productName as productName, ");
            prepareQuery.append("mfrs.productUnit as unit, ");
            prepareQuery.append("mfrs.quantity as quantity, ");
            prepareQuery.append("sl.productAdditionalUnit as additionalUnit, ");
            prepareQuery.append("mfrs.quantityinadditionalunit as additionalQuantity ");
            prepareQuery.append("FROM materialflowresources_resourcestockdto as mfrs ");
            prepareQuery.append("JOIN materialflowresources_storagelocationdto as sl ");
            prepareQuery.append("ON mfrs.productnumber = sl.productnumber ");
            prepareQuery.append("WHERE mfrs.productnumber = :productNumber ");
            prepareQuery.append("AND mfrs.locationNumber IN (:locationNumbers) ");



            params.put("productNumber", productNumber);
            params.put("locationNumbers", locationNumbers);

            list = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(StorageLocationsForProductDto.class));
            return list;
        }
        return list;
    }

    @Override
    public List<ExtendedResourceDetailsDto> getMoreResourceDetails(String resourceNumber) {
        List<ExtendedResourceDetailsDto> resourceDetailsDto = new ArrayList<>();
        Map<String, Object> params = Maps.newHashMap();


        if(resourceNumber != null && !resourceNumber.isEmpty()) {
            StringBuilder prepareQuery = new StringBuilder();

            prepareQuery.append("SELECT ");
            prepareQuery.append("dto.productName as productName, ");
            prepareQuery.append("dto.productNumber as productNumber, ");
            prepareQuery.append("dto.locationNumber as locationNumber, ");
            prepareQuery.append("dto.storageLocationNumber as storageLocationNumber, ");
            prepareQuery.append("dto.palletNumber as palletNumber, ");
            prepareQuery.append("dto.batchNumber as batchNumber, ");
            prepareQuery.append("dto.productionDate as productionDate, ");
            prepareQuery.append("dto.expirationDate as expirationDate, ");
            prepareQuery.append("dto.givenUnit as additionalUnit, ");
            prepareQuery.append("dto.quantity as quantity, ");
            prepareQuery.append("dto.productUnit as unit, ");
            prepareQuery.append("dto.quantityinadditionalunit as additionalQuantity ");
            prepareQuery.append("FROM materialflowresources_resourcedto as dto ");
            prepareQuery.append("WHERE dto.number = :resourceNumber");

            params.put("resourceNumber", resourceNumber);

            resourceDetailsDto = jdbcTemplate.query(String.valueOf(prepareQuery), params, new BeanPropertyRowMapper(ExtendedResourceDetailsDto.class));
            return resourceDetailsDto;
        }
        return resourceDetailsDto;
    }

    private DataDefinition getProductDD() {
        return dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PRODUCT);
    }

    private DataDefinition getLocationDD() {
        return dataDefinitionService.get(MaterialFlowConstants.PLUGIN_IDENTIFIER, MaterialFlowConstants.MODEL_LOCATION);
    }

    private DataDefinition getResourceDD() {
        return dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_RESOURCE);
    }

    private DataDefinition getResourceStockDtoDD() {
        return dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_RESOURCE_STOCK_DTO);
    }

}
