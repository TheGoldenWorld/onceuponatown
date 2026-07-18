## 🏘️ Once Upon a Town v0.0.15 | Autonomous housing and autonomous upgrades !
### Autonomous villages:
- Made the system of autonomous building choice easier to understand, the village is now going to preselect the next building so you can bring the resources to found it
- Created a new system of autonomous housing, the village is going to select the houses it needs to be built to expend to the next era following the resident gaps needed to be filled
- The housing system is also going to announce the next house that will be selected in order to indicate to the player what resources to gather
- Created an auto upgrade system that will preselect some structures to upgrade these during the building phase, you will be able to found the upgrades as the village announce each upgrade
- The auto upgrade feature is capped following the max level gated by the era, helping the village to slowly evolve in space and look over the eras 
### Gameplay changes:
- Added a new era 4 for some orientations, the ones that had another custom-building to be built (beekeeper, leather workshop, granary)
- Matching all the new town center NBT upgrades, I have added way more resource production to each of them. The town hall are now stronger
- Made the houses needing white wools instead of beds to avoid slowing too much the early progress of the village
- Each max level upgrade is going to be defined by the era of the village, it means, you will need to reach a higher era to upgrade some buildings further
### System improvements: 
- Added a slider instead of arrows inside the NBT widget preview to make the discovery more smooth and pleasant
- The stock is not going to absorb extra resources from the trading zone, and you will not be able to sell extra resources if the village cannot stock it
- Made the town hall upgrade also displayer with a padlock on top of it, so it cannot be removed by mistake by the player inside the construction queue
- Harmonized the wording into the village announcement system between the logs inside the widget and what is announced into the player chat
- Added a system to add a max amount of unit per resource so beds are not overproduced and flood the village inventory
- Placed the most recent note in the quest panel on top instead of at the bottom
- Fixed an issue with the lumberjack clothes not showing
- Made a ton of internal code changes to allow datapack creation in the future