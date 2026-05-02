document.addEventListener("DOMContentLoaded", function () {
    const shopSelect = document.getElementById("shopSelect");
    const dateSelect = document.getElementById("dateSelect");
    const timeSelect = document.getElementById("timeSelect");
    const therapistBlocks = document.getElementById("therapistBlocks");
    const addTherapistBtn = document.getElementById("addTherapistBtn");
    const bookingForm = document.getElementById("bookingMultiForm");
    const payloadInput = document.getElementById("multiPayload");

    // Booking link page does not show a shop dropdown. In that mode, shopId comes from hidden input.

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

    function fetchEmployees(shopId, employeeSelect) {
        console.log(`[BookingUI] Fetching employees for shopId=${shopId}...`);
        fetch(`/api/employees?shop_id=${shopId}`)
            .then(res => res.json())
            .then(data => {
                const employees = data.employees || data;
                console.log("[BookingUI] Employees fetched:", employees);
                employeeSelect.innerHTML = "";

                if (!employees || employees.length === 0) {
                    const opt = document.createElement("option");
                    opt.value = "";
                    opt.textContent = "No employees found";
                    opt.disabled = true;
                    opt.selected = true;
                    employeeSelect.appendChild(opt);
                    console.warn("[BookingUI] No employees returned for shopId=", shopId);
                    return;
                }

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

    function fetchServices(employeeId, serviceContainer) {
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

                    const textWrap = document.createElement("div");

                    const name = document.createElement("div");
                    name.className = "service-name";
                    name.textContent = service.name;

                    const meta = document.createElement("div");
                    meta.className = "service-meta";
                    meta.textContent = `${service.duration} min • ${service.price} kr`;

                    textWrap.appendChild(name);
                    textWrap.appendChild(meta);

                    const container = document.createElement("label");
                    container.className = "service-item";
                    container.appendChild(checkbox);
                    container.appendChild(textWrap);

                    // visual feedback for selection
                    checkbox.addEventListener("change", () => {
                        container.dataset.checked = checkbox.checked ? "true" : "false";
                    });
                    container.dataset.checked = "false";

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
        const date = dateSelect.value;

        const blocks = getTherapistDataFromDom();
        if (blocks.length === 0) {
            console.warn("[BookingUI] No therapists selected.");
            return;
        }

        // Validate each block has employee + at least one service
        for (const b of blocks) {
            if (!b.employeeId || b.serviceIds.length === 0) {
                console.warn("[BookingUI] Missing employee/services in therapist blocks.");
                return;
            }
        }

        console.log(`[BookingUI] Fetching timeslots (multi) for shopId=${shopId}, date=${date}, blocks=${blocks.length}`);

        if (!shopId || !date) {
            console.warn("[BookingUI] Missing required parameters for timeslots fetch.");
            return;
        }

        const payload = {
            shopId: parseInt(shopId, 10),
            date,
            blocks
        };

        fetch(`/api/timeslots/multi`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        })
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
                        // Backend expects appointment_time in "yyyy-MM-dd HH:mm" (space, not 'T').
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
        }
        return null;
    }

    function createTherapistBlock(index) {
        const block = document.createElement("div");
        block.className = "therapist-block";
        block.dataset.index = String(index);

        const head = document.createElement("div");
        head.className = "therapist-head";

        const title = document.createElement("div");
        title.className = "therapist-title";
        title.textContent = `Therapist ${index + 1}`;

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "link-btn danger";
        remove.textContent = "Remove";
        remove.addEventListener("click", () => {
            block.remove();
            renumberTherapists();
            fetchTimeSlots();
        });

        head.appendChild(title);
        head.appendChild(remove);

        const employeeWrap = document.createElement("div");
        const employeeLabel = document.createElement("label");
        employeeLabel.textContent = "Employee";

        const employeeSelect = document.createElement("select");
        employeeSelect.required = true;
        employeeSelect.innerHTML = `<option value="">Select an employee</option>`;

        employeeWrap.appendChild(employeeLabel);
        employeeWrap.appendChild(employeeSelect);

        const servicesWrap = document.createElement("div");
        const servicesLabel = document.createElement("label");
        servicesLabel.textContent = "Services";
        const services = document.createElement("div");
        services.className = "services";
        servicesWrap.appendChild(servicesLabel);
        servicesWrap.appendChild(services);

        // wire events
        employeeSelect.addEventListener("change", () => {
            fetchServices(employeeSelect.value, services);
        });

        services.addEventListener("change", () => {
            fetchTimeSlots();
        });

        block.appendChild(head);
        block.appendChild(employeeWrap);
        block.appendChild(servicesWrap);

        // Load employees based on shop
        const shopId = getShopId();
        if (shopId) {
            fetchEmployees(shopId, employeeSelect);
        }

        return block;
    }

    function renumberTherapists() {
        const blocks = therapistBlocks.querySelectorAll(".therapist-block");
        blocks.forEach((b, i) => {
            b.dataset.index = String(i);
            const title = b.querySelector(".therapist-title");
            if (title) title.textContent = `Therapist ${i + 1}`;
        });
        // hide remove button if only one block
        blocks.forEach((b) => {
            const btn = b.querySelector(".link-btn.danger");
            if (btn) btn.style.display = blocks.length <= 1 ? "none" : "inline";
        });
    }

    function getDurationForServiceIds(serviceIds) {
        // duration is stored on checkboxes (dataset.duration)
        let sum = 0;
        for (const sid of serviceIds) {
            const cb = therapistBlocks.querySelector(`input[type=checkbox][value="${sid}"]`);
            if (cb?.dataset?.duration) sum += parseInt(cb.dataset.duration, 10) || 0;
        }
        return sum;
    }

    function getTherapistDataFromDom() {
        const blocks = Array.from(therapistBlocks.querySelectorAll(".therapist-block"));
        return blocks.map(b => {
            const employeeSelect = b.querySelector("select");
            const checked = b.querySelectorAll("input[type=checkbox]:checked");
            const serviceIds = Array.from(checked)
                .map(cb => parseInt(cb.value, 10))
                .filter(n => !Number.isNaN(n));
            const duration = Array.from(checked)
                .reduce((sum, cb) => sum + (parseInt(cb.dataset.duration, 10) || 0), 0);
            return {
                employeeId: employeeSelect?.value ? parseInt(employeeSelect.value, 10) : null,
                serviceIds,
                duration,
            };
        });
    }

    // ========== Event Listeners ==========

    shopSelect?.addEventListener("change", () => {
        console.log("[BookingUI] shopSelect changed:", shopSelect.value);
        // reload employees for each therapist block
        const blocks = therapistBlocks?.querySelectorAll(".therapist-block") || [];
        blocks.forEach(b => {
            const employeeSelect = b.querySelector("select");
            if (employeeSelect) fetchEmployees(shopSelect.value, employeeSelect);
        });
    });

    dateSelect?.addEventListener("change", () => {
        console.log("[BookingUI] dateSelect changed:", dateSelect.value);
        fetchTimeSlots();
    });

    addTherapistBtn?.addEventListener("click", () => {
        const index = therapistBlocks.querySelectorAll(".therapist-block").length;
        therapistBlocks.appendChild(createTherapistBlock(index));
        renumberTherapists();
        fetchTimeSlots();
    });

    bookingForm?.addEventListener("submit", (e) => {
        // Pack multi-booking details as JSON in hidden field
        const payload = {
            appointmentTime: timeSelect.value,
            date: dateSelect.value,
            blocks: getTherapistDataFromDom(),
        };
        if (payloadInput) payloadInput.value = JSON.stringify(payload);
    });

    // ========== Initialization ==========

    if (shopSelect) {
        fetchShops();
    }

    // Ensure at least 1 therapist block exists
    if (therapistBlocks) {
        therapistBlocks.appendChild(createTherapistBlock(0));
        renumberTherapists();
    }

    populateDateOptions();
});
