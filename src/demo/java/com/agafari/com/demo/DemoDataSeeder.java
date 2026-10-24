package com.agafari.com.demo;

import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.enums.SpiceLevel;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the local demo restaurant on every start of the {@code demo} profile:
 * one owner, one restaurant, one active PUBLIC menu, five categories and twelve items.
 * Refuses to run against anything but the in-memory H2 demo database.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    /** Fixed ids keep the demo URLs the same across restarts. */
    public static final String OWNER_ID = "00000000-0000-4000-8000-000000000001";
    public static final String BUSINESS_ID = "00000000-0000-4000-8000-0000000000b1";
    public static final String MENU_ID = "00000000-0000-4000-8000-0000000000e1";

    private final PasswordEncoder passwordEncoder;
    private final UserRepository users;
    private final BusinessRepository businesses;
    private final BusinessHoursRepository businessHours;
    private final MenuTypeRepository menuTypes;
    private final MenuRepository menus;
    private final CategoryRepository categories;
    private final MenuItemRepository items;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${demo.owner.email}")
    private String ownerEmail;

    @Value("${demo.owner.password}")
    private String ownerPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!datasourceUrl.startsWith("jdbc:h2:mem:")) {
            throw new IllegalStateException("Demo seed data may only be loaded into the in-memory H2 demo database");
        }
        if (ownerPassword == null || ownerPassword.length() < 12) {
            throw new IllegalStateException("DEMO_OWNER_PASSWORD must be set (at least 12 characters); see .env.demo");
        }
        if (users.findByEmail(ownerEmail).isPresent()) {
            log.info("event=demo.seed.skipped reason=already_seeded");
            return;
        }

        List.of("Breakfast", "Lunch", "Dinner", "Drinks").forEach(this::menuType);

        // Same rows as owner registration (AuthService.register), with fixed ids so demo URLs stay stable
        User owner = new User();
        owner.setId(OWNER_ID);
        owner.setFullName("Demo Owner");
        owner.setEmail(ownerEmail);
        owner.setPasswordHash(passwordEncoder.encode(ownerPassword));
        owner.setRole(UserRole.VENUE);
        owner.setStatus(UserStatus.ACTIVE);
        owner = users.save(owner);

        Business business = new Business();
        business.setId(BUSINESS_ID);
        business.setOwner(owner);
        business.setName("Abol Demo Café");
        business.setBusinessPhone("+251 11 000 0000");
        business.setStreetAddress("Bole Road 1");
        business.setCity("Addis Ababa");
        business.setState("Addis Ababa");
        business.setZipcode("1000");
        business.setCustomSubdomain("abol-demo.demo.agafari.test");
        business.setOpen24_7(true);
        business = businesses.save(business);

        for (DayOfWeekEnum day : DayOfWeekEnum.values()) {
            BusinessHours hours = new BusinessHours();
            hours.setId(UUID.randomUUID().toString());
            hours.setBusiness(business);
            hours.setDayOfWeek(day);
            hours.setOpen(true);
            businessHours.save(hours);
        }

        Menu menu = new Menu();
        menu.setId(MENU_ID);
        menu.setBusiness(business);
        menu.setMenuType(menuType("Lunch"));
        menu.setName("All-Day Menu");
        menu.setDescription("Ethiopian classics, served all day");
        menu.setActive(true);
        menu.setVisibility(MenuVisibility.PUBLIC);
        menu = menus.save(menu); // assigned id: save() merges and returns the managed instance

        Category breakfast = category(menu, "Breakfast", 0);
        Category mains = category(menu, "Mains", 1);
        Category vegan = category(menu, "Fasting (Vegan)", 2);
        Category desserts = category(menu, "Desserts", 3);
        Category drinks = category(menu, "Drinks", 4);

        item(breakfast, "Chechebsa", "Shredded flatbread fried with berbere and spiced butter, served with honey",
                "180.00", MenuItemStatus.AVAILABLE, SpiceLevel.MILD, List.of("kita", "berbere", "niter kibbeh", "honey"), List.of("gluten", "dairy"), 15, true);
        item(breakfast, "Ful Medames", "Slow-cooked fava beans with tomato, onion, jalapeño and olive oil",
                "160.00", MenuItemStatus.AVAILABLE, SpiceLevel.MEDIUM, List.of("fava beans", "tomato", "onion", "jalapeño", "olive oil"), List.of(), 10, false);
        item(breakfast, "Enkulal Firfir", "Scrambled eggs with tomato, onion and green pepper",
                "150.00", MenuItemStatus.OUT_OF_STOCK, SpiceLevel.MILD, List.of("eggs", "tomato", "onion", "green pepper"), List.of("eggs"), 10, false);

        item(mains, "Doro Wat", "Chicken stewed in berbere sauce with a hard-boiled egg, served with injera",
                "420.00", MenuItemStatus.AVAILABLE, SpiceLevel.HOT, List.of("chicken", "berbere", "onion", "egg", "injera"), List.of("eggs", "gluten"), 25, true);
        item(mains, "Beef Tibs", "Sautéed beef cubes with rosemary, onion and jalapeño",
                "380.00", MenuItemStatus.AVAILABLE, SpiceLevel.MEDIUM, List.of("beef", "rosemary", "onion", "jalapeño"), List.of(), 20, false);
        item(mains, "Kitfo", "Minced lean beef with mitmita and spiced butter, served with ayib",
                "450.00", MenuItemStatus.UNAVAILABLE, SpiceLevel.HOT, List.of("beef", "mitmita", "niter kibbeh", "ayib"), List.of("dairy"), 15, false);

        item(vegan, "Shiro Wat", "Smooth chickpea stew simmered with garlic and berbere",
                "220.00", MenuItemStatus.AVAILABLE, SpiceLevel.MEDIUM, List.of("chickpea flour", "garlic", "berbere", "onion"), List.of(), 15, false);
        item(vegan, "Beyaynetu", "Fasting platter of lentils, split peas, cabbage and greens on injera",
                "320.00", MenuItemStatus.OUT_OF_STOCK, SpiceLevel.MILD, List.of("red lentils", "split peas", "cabbage", "collard greens", "injera"), List.of("gluten"), 20, true);

        item(desserts, "Baklava", "Layered filo pastry with walnuts and honey syrup",
                "140.00", MenuItemStatus.AVAILABLE, SpiceLevel.NONE, List.of("filo", "walnuts", "honey"), List.of("gluten", "nuts"), 5, false);
        item(desserts, "Seasonal Fruit Plate", "Papaya, mango and banana with lime",
                "120.00", MenuItemStatus.UNAVAILABLE, SpiceLevel.NONE, List.of("papaya", "mango", "banana", "lime"), List.of(), 5, false);

        item(drinks, "Ethiopian Coffee (Buna)", "Traditionally roasted and brewed in a jebena",
                "90.00", MenuItemStatus.AVAILABLE, SpiceLevel.NONE, List.of("coffee"), List.of(), 10, true);
        item(drinks, "Spris Juice", "Layered avocado, mango and papaya juice",
                "130.00", MenuItemStatus.AVAILABLE, SpiceLevel.NONE, List.of("avocado", "mango", "papaya"), List.of(), 5, false);

        log.info("event=demo.seed.done businessId={} menuId={} categories=5 items=12", business.getId(), menu.getId());
    }

    private MenuType menuType(String name) {
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

    private Category category(Menu menu, String name, int sortOrder) {
        Category category = new Category();
        category.setMenu(menu);
        category.setName(name);
        category.setSortOrder(sortOrder);
        category.setActive(true);
        return categories.save(category);
    }

    private void item(Category category, String name, String description, String price, MenuItemStatus status,
                      SpiceLevel spice, List<String> ingredients, List<String> allergens, int prepMinutes, boolean featured) {
        MenuItem item = new MenuItem();
        item.setMenu(category.getMenu());
        item.setCategory(category);
        item.setName(name);
        item.setDescription(description);
        item.setPrice(new BigDecimal(price));
        item.setStatus(status);
        item.setSpiceLevel(spice);
        item.setIngredients(ingredients);
        item.setAllergens(allergens);
        item.setTags(featured ? List.of("chef's pick") : List.of());
        item.setPrepTimeMinutes(prepMinutes);
        item.setFeatured(featured);
        item.setSortOrder((int) items.countByCategory_Id(category.getId()));
        items.save(item);
    }
}
