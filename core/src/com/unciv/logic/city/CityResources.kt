package com.unciv.logic.city

import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueType

object CityResources {

    /** Returns ALL resources, city-wide and civ-wide */
    fun getResourcesGeneratedByCity(city: City): ResourceSupplyList {
        val resourceModifers = HashMap<String, Float>()
        for (resource in city.civ.gameInfo.ruleset.tileResources.values)
            resourceModifers[resource.name] = city.civ.getResourceModifier(resource)

        val cityResources = getResourcesGeneratedByCityNotIncludingBuildings(city, resourceModifers)
        addCityResourcesGeneratedFromUniqueBuildings(city, cityResources, resourceModifers)

        return cityResources
    }


    /** Only for *city-wide* resources - civ-wide resources should use civ-level resources */
    fun getCityResourcesAvailableToCity(city: City): ResourceSupplyList {
        val resourceModifers = HashMap<String, Float>()
        for (resource in city.civ.gameInfo.ruleset.tileResources.values)
            resourceModifers[resource.name] = city.civ.getResourceModifier(resource)

        val cityResources = getResourcesGeneratedByCityNotIncludingBuildings(city, resourceModifers)
        // We can't use getResourcesGeneratedByCity directly, because that would include the resources generated by buildings -
        //   which are part of the civ-wide uniques, so we'd be getting them twice!
        // This way we get them once, but it is ugly, I welcome other ideas :/
        getCityResourcesFromCiv(city, cityResources, resourceModifers)

        cityResources.removeAll { !it.resource.hasUnique(UniqueType.CityResource) }

        return cityResources
    }


    private fun getResourcesGeneratedByCityNotIncludingBuildings(city: City, resourceModifers: HashMap<String, Float>): ResourceSupplyList {
        val cityResources = ResourceSupplyList()

        addResourcesFromTiles(city, resourceModifers, cityResources)

        addResourceFromUniqueImprovedTiles(city, cityResources, resourceModifers)

        removeCityResourcesRequiredByBuildings(city, cityResources)

        if (city.civ.isCityState && city.isCapital() && city.civ.cityStateResource != null) {
            cityResources.add(
                city.getRuleset().tileResources[city.civ.cityStateResource]!!,
                "Mercantile City-State"
            )
        }

        return cityResources
    }

    private fun addCityResourcesGeneratedFromUniqueBuildings(city: City, cityResources: ResourceSupplyList, resourceModifer: HashMap<String, Float>) {
        for (unique in city.getMatchingUniques(UniqueType.ProvidesResources, StateForConditionals(city), false)) { // E.G "Provides [1] [Iron]"
            val resource = city.getRuleset().tileResources[unique.params[1]]
                ?: continue
            cityResources.add(
                resource, unique.getSourceNameForUser(),
                (unique.params[0].toFloat() * resourceModifer[resource.name]!!).toInt()
            )
        }
    }

    /** Gets the number of resources available to this city
     * Accommodates both city-wide and civ-wide resources */
    fun getAvailableResourceAmount(city: City, resourceName: String): Int {
        val resource = city.getRuleset().tileResources[resourceName] ?: return 0

        if (resource.hasUnique(UniqueType.CityResource))
            return getCityResourcesAvailableToCity(city).asSequence().filter { it.resource == resource }.sumOf { it.amount }
        return city.civ.getResourceAmount(resourceName)
    }

    private fun addResourcesFromTiles(city: City, resourceModifer: HashMap<String, Float>, cityResources: ResourceSupplyList) {
        for (tileInfo in city.getTiles().filter { it.resource != null }) {
            val resource = tileInfo.tileResource
            val amount = getTileResourceAmount(city, tileInfo) * resourceModifer[resource.name]!!
            if (amount > 0) cityResources.add(resource, "Tiles", amount.toInt())
        }
    }

    private fun addResourceFromUniqueImprovedTiles(city: City, cityResources: ResourceSupplyList, resourceModifer: HashMap<String, Float>) {
        for (tileInfo in city.getTiles().filter { it.getUnpillagedImprovement() != null }) {
            val stateForConditionals = StateForConditionals(city.civ, city, tile = tileInfo)
            val tileImprovement = tileInfo.getUnpillagedTileImprovement()
            for (unique in tileImprovement!!.getMatchingUniques(UniqueType.ProvidesResources, stateForConditionals)) {
                val resource = city.getRuleset().tileResources[unique.params[1]] ?: continue
                cityResources.add(
                    resource, "Improvements",
                    (unique.params[0].toFloat() * resourceModifer[resource.name]!!).toInt()
                )
            }
            for (unique in tileImprovement.getMatchingUniques(UniqueType.ConsumesResources, stateForConditionals)) {
                val resource = city.getRuleset().tileResources[unique.params[1]] ?: continue
                cityResources.add(
                    resource, "Improvements",
                    -1 * unique.params[0].toInt()
                )
            }
        }
    }

    private fun removeCityResourcesRequiredByBuildings(city: City, cityResources: ResourceSupplyList) {
        val freeBuildings = city.civ.civConstructions.getFreeBuildingNames(city)
        for (building in city.cityConstructions.getBuiltBuildings()) {
            // Free buildings cost no resources
            if (building.name in freeBuildings) continue
            cityResources.subtractResourceRequirements(building.getResourceRequirementsPerTurn(StateForConditionals(city)), city.getRuleset(), "Buildings")
        }
    }

    private fun getCityResourcesFromCiv(city: City, cityResources: ResourceSupplyList, resourceModifers: HashMap<String, Float>) {
        // This includes the uniques from buildings, from this and all other cities
        for (unique in city.getMatchingUniques(UniqueType.ProvidesResources, StateForConditionals(city))) { // E.G "Provides [1] [Iron]"
            val resource = city.getRuleset().tileResources[unique.params[1]]
                ?: continue
            cityResources.add(
                resource, unique.getSourceNameForUser(),
                (unique.params[0].toFloat() * resourceModifers[resource.name]!!).toInt()
            )
        }
    }

    private fun getTileResourceAmount(city: City, tile: Tile): Int {
        if (tile.resource == null) return 0
        if (!tile.providesResources(city.civ)) return 0

        val resource = tile.tileResource
        var amountToAdd = if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount
        else 1
        if (resource.resourceType == ResourceType.Luxury
            && city.containsBuildingUnique(UniqueType.ProvidesExtraLuxuryFromCityResources))
            amountToAdd += 1

        return amountToAdd
    }
}
