package com.agafari.com.support;

import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.BusinessHours;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.entity.MenuType;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.jpa.repository.MenuTypeRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.JwtService;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Builds tenant data for integration tests. Example — two isolated tenants:
 * <pre>
 * var a = data.tenant("alpha");
 * var b = data.tenant("bravo");
 * data.item(data.category(data.menu(a, "Lunch"), "Mains"), "Burger");
 * data.item(data.category(data.menu(b, "Lunch"), "Mains"), "Pasta");
 * mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(a)));
 * </pre>
 */
@TestComponent
public class TestDataFactory {

    public static final String PASSWORD = "Test-Passw0rd!";

    public record Tenant(User owner, Business business) {
        public String businessId() {
            return business.getId();
        }
    }

    private final UserRepository users;
    private final BusinessRepository businesses;
    private final MenuTypeRepository menuTypes;
    private final MenuRepository menus;
    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final BusinessHoursRepository businessHours;

    public TestDataFactory(UserRepository users, BusinessRepository businesses, MenuTypeRepository menuTypes,
                           MenuRepository menus, CategoryRepository categories, MenuItemRepository items,
                           PasswordEncoder passwordEncoder, JwtService jwtService, JdbcTemplate jdbc,
                           BusinessHoursRepository businessHours) {
        this.users = users;
        this.businesses = businesses;
        this.menuTypes = menuTypes;
        this.menus = menus;
        this.categories = categories;
        this.items = items;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.businessHours = businessHours;
    }

    /** Active VENUE owner with one business. Login password: {@link #PASSWORD}. */
    public Tenant tenant(String label) {
        return tenant(label, UserRole.VENUE);
    }

    /** Active user with the given role and one business (AGF-S1-06 role tests). */
    public Tenant tenant(String label, UserRole role) {
        User owner = new User();
        owner.setId(UUID.randomUUID().toString());
        owner.setFullName(label + " Owner");
        owner.setEmail(label + "@test.agafari.local");
        owner.setPasswordHash(passwordEncoder.encode(PASSWORD));
        owner.setRole(role);
        owner.setStatus(UserStatus.ACTIVE);
        users.save(owner);

        Business business = new Business();
        business.setId(UUID.randomUUID().toString());
        business.setOwner(owner);
        business.setName(label + " Restaurant");
        business.setBusinessPhone("+251911000000");
        business.setStreetAddress("1 Test Street");
        business.setCity("Addis Ababa");
        business.setState("Addis Ababa");
        business.setZipcode("");
        business.setCustomSubdomain(label + ".test.agafari.local");
        businesses.save(business);
        return new Tenant(owner, business);
    }

    /** AGF E4-M1: an ACTIVE platform admin (no business), as the bootstrap creates it. */
    public User admin(String label) {
        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setFullName(label + " Admin");
        admin.setEmail(label + "@admin.agafari.local");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        return users.save(admin);
    }

    /** AGF E4-M1: Authorization header value for a platform admin. */
    public String adminBearer(User admin) {
        return "Bearer " + jwtService.signToken(admin.getEmail(), admin.getId(), null, admin.getRole().name());
    }

    /** Authorization header value for the tenant's owner. */
    public String bearer(Tenant tenant) {
        User owner = tenant.owner();
        return "Bearer " + jwtService.signToken(owner.getEmail(), owner.getId(), tenant.businessId(), owner.getRole().name());
    }

    /** Seven open days, 09:00-17:00. */
    public void hours(Tenant tenant) {
        for (DayOfWeekEnum day : DayOfWeekEnum.values()) {
            BusinessHours row = new BusinessHours();
            row.setId(UUID.randomUUID().toString());
            row.setBusiness(tenant.business());
            row.setDayOfWeek(day);
            row.setOpen(true);
            row.setStartTime("09:00");
            row.setEndTime("17:00");
            businessHours.save(row);
        }
    }

    public MenuType menuType(String name) {
        return menuTypes.findAll().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    MenuType type = new MenuType();
                    type.setId(UUID.randomUUID().toString());
                    type.setName(name);
                    return menuTypes.save(type);
                });
    }

    /** Active, PUBLIC menu. */
    public Menu menu(Tenant tenant, String name) {
        return menu(tenant, name, true, MenuVisibility.PUBLIC);
    }

    public Menu menu(Tenant tenant, String name, boolean active, MenuVisibility visibility) {
        Menu menu = new Menu();
        menu.setId(UUID.randomUUID().toString());
        menu.setBusiness(tenant.business());
        menu.setMenuType(menuType("Lunch"));
        menu.setName(name);
        menu.setActive(active);
        menu.setVisibility(visibility);
        return menus.save(menu);
    }

    /** Active category. */
    public Category category(Menu menu, String name) {
        return category(menu, name, true);
    }

    public Category category(Menu menu, String name, boolean active) {
        Category category = new Category();
        category.setMenu(menu);
        category.setName(name);
        category.setActive(active);
        return categories.save(category);
    }

    /** AVAILABLE item priced 10.00 with one ingredient. */
    public MenuItem item(Category category, String name) {
        return item(category, name, MenuItemStatus.AVAILABLE);
    }

    public MenuItem item(Category category, String name, MenuItemStatus status) {
        MenuItem item = new MenuItem();
        item.setMenu(category.getMenu());
        item.setCategory(category);
        item.setName(name);
        item.setPrice(new BigDecimal("10.00"));
        item.setIngredients(List.of("ingredient"));
        item.setStatus(status);
        return items.save(item);
    }

    /** Removes all rows so every test starts from an empty database, except the seeded menu types (reference data). */
    public void deleteAll() {
        jdbc.execute("TRUNCATE TABLE menu_item, category, menu, business_hours, business, users CASCADE");
        jdbc.execute("TRUNCATE TABLE business_subscription, admin_audit_event, monetization_config"); // AGF E4-M1..M3: table-level resets (the row triggers block UPDATE/DELETE only); the seeded plans stay
        // AGF-S1-09: the seeded menu types (Flyway V2, ids 'mt-…') are reference data; only test-created types go
        jdbc.execute("DELETE FROM menu_type WHERE id NOT LIKE 'mt-%'");
    }
}
