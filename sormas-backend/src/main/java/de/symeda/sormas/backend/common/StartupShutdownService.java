/*******************************************************************************
 * SORMAS® - Surveillance Outbreak Response Management & Analysis System
 * Copyright © 2016-2018 Helmholtz-Zentrum für Infektionsforschung GmbH (HZI)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/
package de.symeda.sormas.backend.common;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.security.RunAs;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.symeda.sormas.api.Disease;
import de.symeda.sormas.api.Language;
import de.symeda.sormas.api.facility.FacilityType;
import de.symeda.sormas.api.i18n.Captions;
import de.symeda.sormas.api.i18n.I18nProperties;
import de.symeda.sormas.api.infrastructure.PointOfEntryDto;
import de.symeda.sormas.api.infrastructure.PointOfEntryType;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.api.utils.DataHelper;
import de.symeda.sormas.backend.caze.CaseService;
import de.symeda.sormas.backend.common.ConfigFacadeEjb.ConfigFacadeEjbLocal;
import de.symeda.sormas.backend.contact.Contact;
import de.symeda.sormas.backend.contact.ContactService;
import de.symeda.sormas.backend.disease.DiseaseConfiguration;
import de.symeda.sormas.backend.disease.DiseaseConfigurationFacadeEjb.DiseaseConfigurationFacadeEjbLocal;
import de.symeda.sormas.backend.disease.DiseaseConfigurationService;
import de.symeda.sormas.backend.epidata.EpiDataService;
import de.symeda.sormas.backend.event.EventParticipantService;
import de.symeda.sormas.backend.facility.Facility;
import de.symeda.sormas.backend.facility.FacilityService;
import de.symeda.sormas.backend.importexport.ImportFacadeEjb.ImportFacadeEjbLocal;
import de.symeda.sormas.backend.infrastructure.PointOfEntry;
import de.symeda.sormas.backend.infrastructure.PointOfEntryService;
import de.symeda.sormas.backend.person.PersonService;
import de.symeda.sormas.backend.region.Community;
import de.symeda.sormas.backend.region.CommunityService;
import de.symeda.sormas.backend.region.District;
import de.symeda.sormas.backend.region.DistrictService;
import de.symeda.sormas.backend.region.Region;
import de.symeda.sormas.backend.region.RegionService;
import de.symeda.sormas.backend.symptoms.SymptomsService;
import de.symeda.sormas.backend.user.User;
import de.symeda.sormas.backend.user.UserService;
import de.symeda.sormas.backend.util.MockDataGenerator;
import de.symeda.sormas.backend.util.ModelConstants;

@Singleton(name = "StartupShutdownService")
@Startup
@RunAs(UserRole._SYSTEM)
@TransactionManagement(TransactionManagementType.CONTAINER)
public class StartupShutdownService {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@PersistenceContext(unitName = ModelConstants.PERSISTENCE_UNIT_NAME)
	protected EntityManager em;
	@PersistenceContext(unitName = ModelConstants.PERSISTENCE_UNIT_NAME_AUDITLOG)
	protected EntityManager emAudit;
	@EJB
	private ConfigFacadeEjbLocal configFacade;
	@EJB
	private UserService userService;
	@EJB
	private CaseService caseService;
	@EJB
	private ContactService contactService;
	@EJB
	private EventParticipantService eventParticipantService;
	@EJB
	private EpiDataService epiDataService;
	@EJB
	private SymptomsService symptomsService;
	@EJB
	private PersonService personService;
	@EJB
	private RegionService regionService;
	@EJB
	private DistrictService districtService;
	@EJB
	private CommunityService communityService;
	@EJB
	private FacilityService facilityService;
	@EJB
	private PointOfEntryService pointOfEntryService;
	@EJB
	private ImportFacadeEjbLocal importFacade;
	@EJB
	private DiseaseConfigurationFacadeEjbLocal diseaseConfigurationFacade;
	@EJB
	private DiseaseConfigurationService diseaseConfigurationService;

	@PostConstruct
	public void startup() {
		logger.info("Initiating automatic database update of main database...");
		
		updateDatabase(em, "/sql/sormas_schema.sql");
		
		logger.info("Initiating automatic database update of audit database...");
		
		updateDatabase(emAudit, "/sql/sormas_audit_schema.sql");
		
		I18nProperties.setDefaultLanguage(Language.fromLocaleString(configFacade.getCountryLocale()));
		
		buildDummyInfrastructureData();
		
		importConstantPointsOfEntry();

		initDefaultUsers();
		
		upgrade();
		
		createImportTemplateFiles();
		
		createMissingDiseaseConfigurations();
		
		configFacade.validateAppUrls();
	}
	
	private void buildDummyInfrastructureData() {

		// Region
		Region region = new Region();
		region.setUuid(DataHelper.createUuid());
		region.setName(I18nProperties.getCaption(Captions.defaultRegion, "Default Region"));
		region.setEpidCode("DEF-REG");
		regionService.ensurePersisted(region);

		// District
		District district = new District();
		district.setUuid(DataHelper.createUuid());
		district.setName(I18nProperties.getCaption(Captions.defaultDistrict, "Default District"));
		district.setRegion(region);
		district.setEpidCode("DIS");
		districtService.ensurePersisted(district);
		
		// Community
		Community community = new Community();
		community.setUuid(DataHelper.createUuid());
		community.setName(I18nProperties.getCaption(Captions.defaultCommunity, "Default Community"));
		community.setDistrict(district);
		communityService.ensurePersisted(community);
		
		// Health Facility
		Facility facility = new Facility();
		facility.setUuid(DataHelper.createUuid());
		facility.setName(I18nProperties.getCaption(Captions.defaultHealthFacility, "Default Health Facility"));
		facility.setCommunity(community);
		facility.setDistrict(district);
		facility.setRegion(region);
		facilityService.ensurePersisted(facility);
		
		// Laboratory
		Facility laboratory = new Facility();
		laboratory.setUuid(DataHelper.createUuid());
		laboratory.setName(I18nProperties.getCaption(Captions.defaultLaboratory, "Default Laboratory"));
		laboratory.setCommunity(community);
		laboratory.setDistrict(district);
		laboratory.setRegion(region);
		laboratory.setType(FacilityType.LABORATORY);
		
		// Point of Entry
		PointOfEntry pointOfEntry = new PointOfEntry();
		pointOfEntry.setUuid(DataHelper.createUuid());
		pointOfEntry.setName(I18nProperties.getCaption(Captions.defaultPointOfEntry, "Default Point Of Entry"));
		pointOfEntry.setDistrict(district);
		pointOfEntry.setRegion(region);
		pointOfEntry.setPointOfEntryType(PointOfEntryType.AIRPORT);
		pointOfEntryService.ensurePersisted(pointOfEntry);
	}

	private void importConstantPointsOfEntry() {
		if (pointOfEntryService.getByUuid(PointOfEntryDto.OTHER_AIRPORT_UUID) == null) {
			PointOfEntry otherAirport = new PointOfEntry();
			otherAirport.setName("OTHER_AIRPORT");
			otherAirport.setUuid(PointOfEntryDto.OTHER_AIRPORT_UUID);
			otherAirport.setActive(true);
			otherAirport.setPointOfEntryType(PointOfEntryType.AIRPORT);
			pointOfEntryService.persist(otherAirport);
		}
		if (pointOfEntryService.getByUuid(PointOfEntryDto.OTHER_SEAPORT_UUID) == null) {
			PointOfEntry otherSeaport = new PointOfEntry();
			otherSeaport.setName("OTHER_SEAPORT");
			otherSeaport.setUuid(PointOfEntryDto.OTHER_SEAPORT_UUID);
			otherSeaport.setActive(true);
			otherSeaport.setPointOfEntryType(PointOfEntryType.SEAPORT);
			pointOfEntryService.persist(otherSeaport);
		}
		if (pointOfEntryService.getByUuid(PointOfEntryDto.OTHER_GROUND_CROSSING_UUID) == null) {
			PointOfEntry otherGC = new PointOfEntry();
			otherGC.setName("OTHER_GROUND_CROSSING");
			otherGC.setUuid(PointOfEntryDto.OTHER_GROUND_CROSSING_UUID);
			otherGC.setActive(true);
			otherGC.setPointOfEntryType(PointOfEntryType.GROUND_CROSSING);
			pointOfEntryService.persist(otherGC);
		}
		if (pointOfEntryService.getByUuid(PointOfEntryDto.OTHER_POE_UUID) == null) {
			PointOfEntry otherPoe = new PointOfEntry();
			otherPoe.setName("OTHER_POE");
			otherPoe.setUuid(PointOfEntryDto.OTHER_POE_UUID);
			otherPoe.setActive(true);
			otherPoe.setPointOfEntryType(PointOfEntryType.OTHER);
			pointOfEntryService.persist(otherPoe);
		}
	}

	private void initDefaultUsers() {
		if (userService.getAll().isEmpty()) {
	
			Region region = regionService.getAll().get(0);
			District district = region.getDistricts().get(0);
			Community community = district.getCommunities().get(0);
			List<Facility> healthFacilities = facilityService.getActiveHealthFacilitiesByCommunity(community, false);
			Facility facility = healthFacilities.size() > 0 ? healthFacilities.get(0) : null;
			List<Facility> laboratories = facilityService.getAllActiveLaboratories(false);
			Facility laboratory = laboratories.size() > 0 ? laboratories.get(0) : null;
			PointOfEntry pointOfEntry = pointOfEntryService.getAllActive().get(0);
	
			// Generate Admin
			User admin = MockDataGenerator.createUser(UserRole.ADMIN, "ad", "min", "sadmin");
			admin.setUserName("admin");
			userService.persist(admin);
	
			// Generate Surveillance Supervisor
			User surveillanceSupervisor = MockDataGenerator.createUser(UserRole.SURVEILLANCE_SUPERVISOR, "Surveillance",
					"Supervisor", "SurvSup");
			surveillanceSupervisor.setUserName("SurvSup");
			surveillanceSupervisor.setRegion(region);
			userService.persist(surveillanceSupervisor);

			// Generate Case Supervisor
			User caseSupervisor = MockDataGenerator.createUser(UserRole.CASE_SUPERVISOR, "Case", "Supervisor",
					"CaseSup");
			caseSupervisor.setUserName("CaseSup");
			caseSupervisor.setRegion(region);
			userService.persist(caseSupervisor);

			// Generate Contact Supervisor
			User contactSupervisor = MockDataGenerator.createUser(UserRole.CONTACT_SUPERVISOR, "Contact", "Supervisor",
					"ContSup");
			contactSupervisor.setUserName("ContSup");
			contactSupervisor.setRegion(region);
			userService.persist(contactSupervisor);

			// Generate Point of Entry Supervisor
			User poeSupervisor = MockDataGenerator.createUser(UserRole.POE_SUPERVISOR, "Point of Entry", "Supervisor",
					"PoeSup");
			poeSupervisor.setUserName("PoeSup");
			poeSupervisor.setRegion(region);
			userService.persist(poeSupervisor);

			// Generate Laboratory Officer
			User laboratoryOfficer = MockDataGenerator.createUser(UserRole.LAB_USER, "Laboratory", "Officer", "LabOff");
			laboratoryOfficer.setUserName("LabOff");
			laboratoryOfficer.setLaboratory(laboratory);
			userService.persist(laboratoryOfficer);

			// Generate Event Officer
			User eventOfficer = MockDataGenerator.createUser(UserRole.EVENT_OFFICER, "Event", "Officer", "EveOff");
			eventOfficer.setUserName("EveOff");
			eventOfficer.setRegion(region);
			userService.persist(eventOfficer);

			// Generate National User
			User nationalUser = MockDataGenerator.createUser(UserRole.NATIONAL_USER, "National", "User", "NatUser");
			nationalUser.setUserName("NatUser");
			userService.persist(nationalUser);

			// Generate National Clinician
			User nationalClinician = MockDataGenerator.createUser(UserRole.NATIONAL_CLINICIAN, "National", "Clinician",
					"NatClin");
			nationalClinician.setUserName("NatClin");
			userService.persist(nationalClinician);
	
			// Generate Surveillance Officer
			User surveillanceOfficer = MockDataGenerator.createUser(UserRole.SURVEILLANCE_OFFICER, "Surveillance",
					"Officer", "SurvOff");
			surveillanceOfficer.setUserName("SurvOff");
			surveillanceOfficer.setRegion(region);
			surveillanceOfficer.setDistrict(district);
			userService.persist(surveillanceOfficer);

			// Generate Hospital Informant
			User hospitalInformant = MockDataGenerator.createUser(UserRole.HOSPITAL_INFORMANT, "Hospital", "Informant",
					"HospInf");
			hospitalInformant.setUserName("HospInf");
			hospitalInformant.setRegion(region);
			hospitalInformant.setDistrict(district);
			hospitalInformant.setHealthFacility(facility);
			hospitalInformant.setAssociatedOfficer(surveillanceOfficer);
			userService.persist(hospitalInformant);

			User poeInformant = MockDataGenerator.createUser(UserRole.POE_INFORMANT, "Poe", "Informant", "PoeInf");
			poeInformant.setUserName("PoeInf");
			poeInformant.setRegion(region);
			poeInformant.setDistrict(district);
			poeInformant.setPointOfEntry(pointOfEntry);
			poeInformant.setAssociatedOfficer(surveillanceOfficer);
			userService.persist(poeInformant);
		}
	}
	
	private void updateDatabase(EntityManager entityManager, String schemaFileName) {
		logger.info("Starting automatic database update...");
		
		boolean hasSchemaVersion = !entityManager.createNativeQuery("SELECT 1 FROM information_schema.tables WHERE table_name = 'schema_version'").getResultList().isEmpty();
		Integer currentVersion;
		if (hasSchemaVersion) {
			currentVersion = (Integer) entityManager.createNativeQuery("SELECT MAX(version_number) FROM schema_version").getSingleResult();	
		} else {
			currentVersion = null;
		}
		File schemaFile = new File(getClass().getClassLoader().getResource(schemaFileName).getFile());
		Scanner scanner = null;
		
		try {
			scanner = new Scanner(schemaFile);
			StringBuilder nextUpdateBuilder = new StringBuilder();
			boolean currentVersionReached = currentVersion == null;
			
			while (scanner.hasNextLine()) {
				String nextLine = scanner.nextLine();
				
				if (nextLine.isEmpty()) {
					continue;
				}
				
				if (nextLine.trim().startsWith("--")) {
					continue;
				}
				
				if (!currentVersionReached) {
					if (nextLine.startsWith("INSERT INTO schema_version (version_number, comment) VALUES (" + currentVersion)) {
						currentVersionReached = true;
					}
					continue;
				}
				
				nextLine = nextLine.replaceAll(":", "\\\\:");
				
				// Add the line to the StringBuilder
				nextUpdateBuilder.append(nextLine).append("\n");
				
				// Perform the current update when the INSERT INTO schema_version statement is reached
				if (nextLine.startsWith("INSERT INTO schema_version")) {
					String newVersion = nextLine.substring(61, nextLine.indexOf(",", 61));
					logger.info("Updating database to version " + newVersion + "...");
					entityManager.createNativeQuery(nextUpdateBuilder.toString()).executeUpdate();
					nextUpdateBuilder = new StringBuilder();
				}
			}
		} catch (FileNotFoundException e) {
			logger.error("Could not find " + schemaFileName + " file. Database update not performed.");
			throw new RuntimeException(e);
		} finally {
			scanner.close();
			logger.info("Database update completed.");
		}
	}

	private void upgrade() {
		@SuppressWarnings("unchecked")
		List<Integer> versionsNeedingUpgrade = em
				.createNativeQuery("SELECT version_number FROM schema_version WHERE upgradeNeeded")
				.getResultList();
		
		// IMPORTANT: Never write code to go through all entities in a table and do something
		// here. This will make the deployment fail when there are too many entities in the database.
		
		for (Integer versionNeedingUpgrade : versionsNeedingUpgrade) {
			switch (versionNeedingUpgrade) {
				case 95:
					// update follow up and status for all contacts
					for (Contact contact : contactService.getAll()) {				
						contactService.updateFollowUpUntilAndStatus(contact);
						contactService.udpateContactStatus(contact);
					}
					break;
					
				default:
					throw new NoSuchElementException(DataHelper.toStringNullable(versionNeedingUpgrade)); 
			} 
			
			int updatedRows = em
				.createNativeQuery("UPDATE schema_version SET upgradeNeeded=false WHERE version_number=?1")
				.setParameter(1, versionNeedingUpgrade)
				.executeUpdate();
			if (updatedRows != 1) {
				logger.error("Could not UPDATE schema_version table. Missing user rights?");
			}
		}		
	}
	
	private void createImportTemplateFiles() {
		try {
			importFacade.generateCaseImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create case import template .csv file.");
		}
		
		try {
			importFacade.generateCaseLineListingImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create line listing import template .csv file.");
		}

		try {
			importFacade.generatePointOfEntryImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create point of entry import template .csv file.");
		}
		
		try {
			importFacade.generatePopulationDataImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create population data import template .csv file.");
		}

		try {
			importFacade.generateRegionImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create region import template .csv file.");
		}
		try {
			importFacade.generateDistrictImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create district import template .csv file.");
		}
		try {
			importFacade.generateCommunityImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create community import template .csv file.");
		}
		try {
			importFacade.generateFacilityLaboratoryImportTemplateFile();
		} catch (IOException e) {
			logger.error("Could not create facility/laboratory import template .csv file.");
		}
	}
	
	private void createMissingDiseaseConfigurations() {
		List<DiseaseConfiguration> diseaseConfigurations = diseaseConfigurationService.getAll();
		List<Disease> configuredDiseases = diseaseConfigurations.stream().map(c -> c.getDisease()).collect(Collectors.toList());
		Arrays.stream(Disease.values()).filter(d -> !configuredDiseases.contains(d)).forEach(d -> {
			DiseaseConfiguration configuration = DiseaseConfiguration.build(d);
			diseaseConfigurationService.ensurePersisted(configuration);
		});
	}

	@PreDestroy
	public void shutdown() {

	}
}
