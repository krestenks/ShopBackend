document.addEventListener("DOMContentLoaded", function () {
    const shopSelect = document.getElementById("shopSelect");
    const employeeSelect = document.getElementById("employeeSelect");
    const serviceContainer = document.getElementById("serviceCheckboxes");
    const dateSelect = document.getElementById("dateSelect");
    const timeSelect = document.getElementById("timeSelect");

    function fetchShops() {
        console.log("[BookingUI] Fetching shops...");
        fetch("/api/shops")
            .then(res => res.json())
            .then(data => {
                const shops = data.shops || data;
                console.log("[BookingUI] Shops fetched:", shops);
                shops.forEach(shop => {
                    const option = document.createElement("option");
                    option.value = shop.id;
                    option.textContent = shop.name;
                    shopSelect.appendChild(option);
                });
                if (shops.length > 0) {
                    shopSelect.dispatchEvent(new Event("change"));
                }
            })
            .catch(err => console.error("[BookingUI] Error fetching shops:", err));
    }

    function fetchEmployees(shopId) {
        console.log(`[BookingUI] Fetching employees for shopId=${shopId}...`);
        fetch(`/api/employees?shop_id=${shopId}`)
            .then(res => res.json())
            .then(data => {
                const employees = data.employees || data;
                console.log("[BookingUI] Employees fetched:", employees);
                employeeSelect.innerHTML = "";
                employees.forEach(emp => {
                    const opt = document.createElement("option");
                    opt.value = emp.id;
                    opt.textContent = emp.name;
                    employeeSelect.appendChild(opt);
                });
                employeeSelect.dispatchEvent(new Event("change"));
            })
            .catch(err => console.error("[BookingUI] Error fetching employees:", err));
    }

    function fetchServices(employeeId) {
        console.log(`[BookingUI] Fetching services for employeeId=${employeeId}...`);
        fetch(`/api/services?employee_id=${employeeId}`)
            .then(response => response.json())
            .then(data => {
                const services = data.services || data;
                console.log("[BookingUI] Services fetched:", services);
                serviceContainer.innerHTML = "";
                services.forEach(service => {
                    const checkbox = document.createElement("input");
                    checkbox.type = "checkbox";
                    checkbox.name = "service_ids";
                    checkbox.value = service.id;
                    checkbox.dataset.duration = service.duration;
                    checkbox.dataset.price = service.price;

                    const label = document.createElement("label");
                    label.textContent = ` ${service.name} (${service.duration} min, ${service.price} kr)`;

                    const container = document.createElement("div");
                    container.appendChild(checkbox);
                    container.appendChild(label);
                    serviceContainer.appendChild(container);
                });
            })
            .catch(err => console.error("[BookingUI] Error fetching services:", err));
    }

    function populateDateOptions() {
        console.log("[BookingUI] Populating date options...");
        dateSelect.innerHTML = "";
        const today = new Date();
        for (let i = 0; i < 7; i++) {
            const date = new Date(today);
            date.setDate(today.getDate() + i);
            const option = document.createElement("option");
            option.value = date.toISOString().split("T")[0];
            option.textContent = date.toLocaleDateString("da-DK", {
                weekday: "short", year: "numeric", month: "short", day: "numeric"
            });
            dateSelect.appendChild(option);
        }
        dateSelect.dispatchEvent(new Event("change"));
    }

    function fetchTimeSlots() {
        const shopId = getShopId();
        const employeeId = employeeSelect.value;
        const selected = serviceContainer.querySelectorAll("input[type=checkbox]:checked");
        const duration = Array.from(selected).reduce((sum, cb) => sum + parseInt(cb.dataset.duration), 0);
        const date = dateSelect.value;

        console.log(`[BookingUI] Fetching timeslots for shopId=${shopId}, employeeId=${employeeId}, date=${date}, duration=${duration}`);

        if (!shopId || !employeeId || duration === 0 || !date) {
            console.warn("[BookingUI] Missing required parameters for timeslots fetch.");
            return;
        }

        fetch(`/api/timeslots?employee_id=${employeeId}&shop_id=${shopId}&date=${date}&duration=${duration}`)
            .then(res => res.json())
            .then(slots => {
                console.log("[BookingUI] Time slots fetched:", slots);
                timeSelect.innerHTML = "";
                if (slots.length === 0) {
                    const opt = document.createElement("option");
                    opt.textContent = "Ingen ledige tider";
                    opt.disabled = true;
                    opt.selected = true;
                    timeSelect.appendChild(opt);
                } else {
                    slots.forEach(time => {
                        const opt = document.createElement("option");
                        opt.value = time;
                        opt.textContent = time;
                        timeSelect.appendChild(opt);
                    });
                }
            })
            .catch(err => console.error("[BookingUI] Error fetching time slots:", err));
    }

function getShopId() {
    if (shopSelect) {
        return shopSelect.value;
    } else {
        // Try hidden input fallback:
        const hiddenShopInput = document.querySelector('input[name="shop_id"]');
        if (hiddenShopInput) {
            return hiddenShopInput.value;
        }
        if (serviceContainer?.dataset?.shopId) {
            return serviceContainer.dataset.shopId;
        }
    }
    return null;
}

    // ========== Event Listeners ==========

    shopSelect?.addEventListener("change", () => {
        console.log("[BookingUI] shopSelect changed:", shopSelect.value);
        fetchEmployees(shopSelect.value);
    });

    employeeSelect?.addEventListener("change", () => {
        console.log("[BookingUI] employeeSelect changed:", employeeSelect.value);
        fetchServices(employeeSelect.value);
    });

    serviceContainer?.addEventListener("change", () => {
        console.log("[BookingUI] service selection changed");
        fetchTimeSlots();
    });

    dateSelect?.addEventListener("change", () => {
        console.log("[BookingUI] dateSelect changed:", dateSelect.value);
        fetchTimeSlots();
    });

    // ========== Initialization ==========

    if (shopSelect) {
        fetchShops();
    } else {
        const shopId = getShopId();
        if (shopId) {
            fetchEmployees(shopId);
        }
    }

    populateDateOptions();
});
