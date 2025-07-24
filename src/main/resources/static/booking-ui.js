document.addEventListener("DOMContentLoaded", function () {
    const shopSelect = document.getElementById("shopSelect");
    const employeeSelect = document.getElementById("employeeSelect");
    const serviceContainer = document.getElementById("serviceCheckboxes");
    const dateSelect = document.getElementById("dateSelect");
    const timeSelect = document.getElementById("timeSelect");

    function fetchShops() {
        fetch("/api/shops")
            .then(res => res.json())
            .then(data => {
                const shops = data.shops || data;
                shops.forEach(shop => {
                    const option = document.createElement("option");
                    option.value = shop.id;
                    option.textContent = shop.name;
                    shopSelect.appendChild(option);
                });
                if (shops.length > 0) {
                    shopSelect.dispatchEvent(new Event("change"));
                }
            });
    }

    function fetchEmployees(shopId) {
        fetch(`/api/employees?shop_id=${shopId}`)
            .then(res => res.json())
            .then(data => {
                const employees = data.employees || data;
                employeeSelect.innerHTML = "";
                employees.forEach(emp => {
                    const opt = document.createElement("option");
                    opt.value = emp.id;
                    opt.textContent = emp.name;
                    employeeSelect.appendChild(opt);
                });
                employeeSelect.dispatchEvent(new Event("change"));
            });
    }

    function fetchServices(employeeId) {
        fetch(`/api/services?employee_id=${employeeId}`)
            .then(response => response.json())
            .then(data => {
                const services = data.services || data;
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
            });
    }

    function populateDateOptions() {
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

        if (!shopId || !employeeId || duration === 0 || !date) return;

        fetch(`/api/timeslots?employee_id=${employeeId}&shop_id=${shopId}&date=${date}&duration=${duration}`)
            .then(res => res.json())
            .then(slots => {
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
            });
    }

    function getShopId() {
        if (shopSelect) {
            return shopSelect.value;
        } else if (serviceContainer?.dataset?.shopId) {
            return serviceContainer.dataset.shopId;
        }
        return null;
    }

    // ========== Event Listeners ==========

    shopSelect?.addEventListener("change", () => {
        fetchEmployees(shopSelect.value);
    });

    employeeSelect?.addEventListener("change", () => {
        fetchServices(employeeSelect.value);
    });

    serviceContainer?.addEventListener("change", fetchTimeSlots);
    dateSelect?.addEventListener("change", fetchTimeSlots);

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
