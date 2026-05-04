document.addEventListener("DOMContentLoaded", function () {
    const dateSelect = document.getElementById("dateSelect");
    const timeSelect = document.getElementById("timeSelect");
    const blocksRoot = document.getElementById("therapistBlocks");
    const bookingForm = document.getElementById("bookingMultiForm");
    const payloadInput = document.getElementById("multiPayload");

    function getShopId() {
        const hiddenShopInput = document.querySelector('input[name="shop_id"]');
        return hiddenShopInput ? hiddenShopInput.value : null;
    }

    function populateDateOptions() {
        if (!dateSelect) return;
        dateSelect.innerHTML = "";
        const today = new Date();
        for (let i = 0; i < 7; i++) {
            const d = new Date(today);
            d.setDate(today.getDate() + i);
            const option = document.createElement("option");
            option.value = d.toISOString().split("T")[0];
            option.textContent = d.toLocaleDateString("da-DK", {
                weekday: "short",
                year: "numeric",
                month: "short",
                day: "numeric",
            });
            dateSelect.appendChild(option);
        }
    }

    async function fetchJson(url) {
        const res = await fetch(url);
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        return res.json();
    }

    async function fetchEmployees(shopId) {
        const data = await fetchJson(`/api/employees?shop_id=${shopId}`);
        return data.employees || data;
    }

    async function fetchServices(employeeId) {
        const data = await fetchJson(`/api/services?employee_id=${employeeId}`);
        return data.services || data;
    }

    function renderServiceCheckboxes(services, servicesContainer) {
        servicesContainer.innerHTML = "";

        services.forEach(service => {
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = String(service.id);
            checkbox.dataset.duration = String(service.duration || 0);
            checkbox.dataset.price = String(service.price || 0);

            const textWrap = document.createElement("div");
            const name = document.createElement("div");
            name.className = "service-name";
            name.textContent = service.name;

            const meta = document.createElement("div");
            meta.className = "service-meta";
            meta.textContent = `${service.duration} min • ${service.price} kr`;

            textWrap.appendChild(name);
            textWrap.appendChild(meta);

            const label = document.createElement("label");
            label.className = "service-item";
            label.dataset.checked = "false";
            label.appendChild(checkbox);
            label.appendChild(textWrap);

            checkbox.addEventListener("change", () => {
                label.dataset.checked = checkbox.checked ? "true" : "false";
                fetchTimeSlots().catch(console.error);
            });

            servicesContainer.appendChild(label);
        });
    }

    function createEmployeeCard(employee) {
        const block = document.createElement("div");
        block.className = "therapist-block";
        block.dataset.employeeId = String(employee.id);

        const head = document.createElement("div");
        head.className = "therapist-head";

        const title = document.createElement("div");
        title.className = "therapist-title";
        title.textContent = employee.name;

        head.appendChild(title);
        block.appendChild(head);

        const servicesWrap = document.createElement("div");
        const servicesLabel = document.createElement("label");
        servicesLabel.textContent = "Services";
        const services = document.createElement("div");
        services.className = "services";
        servicesWrap.appendChild(servicesLabel);
        servicesWrap.appendChild(services);
        block.appendChild(servicesWrap);

        // Load services async
        fetchServices(employee.id)
            .then(list => {
                renderServiceCheckboxes(list, services);
            })
            .catch(err => {
                console.error("[BookingUI] Error fetching services:", err);
                services.innerHTML = `<div class="note">Could not load services</div>`;
            });

        return block;
    }

    function getSelectedBlocks() {
        // Include only employees that have >= 1 checked service
        const blocks = Array.from(blocksRoot.querySelectorAll(".therapist-block"));
        return blocks
            .map(b => {
                const employeeId = parseInt(b.dataset.employeeId || "", 10);
                const checked = Array.from(b.querySelectorAll("input[type=checkbox]:checked"));
                const serviceIds = checked
                    .map(cb => parseInt(cb.value, 10))
                    .filter(n => !Number.isNaN(n));
                const duration = checked.reduce((sum, cb) => sum + (parseInt(cb.dataset.duration, 10) || 0), 0);
                return { employeeId, serviceIds, duration };
            })
            .filter(b => b.employeeId && b.serviceIds.length > 0);
    }

    async function fetchTimeSlots() {
        const shopId = getShopId();
        const date = dateSelect?.value;
        if (!shopId || !date || !timeSelect) return;

        const blocks = getSelectedBlocks();
        if (blocks.length === 0) {
            timeSelect.innerHTML = "";
            const opt = document.createElement("option");
            opt.textContent = "Select services above";
            opt.disabled = true;
            opt.selected = true;
            timeSelect.appendChild(opt);
            return;
        }

        const payload = {
            shopId: parseInt(shopId, 10),
            date,
            blocks: blocks.map(b => ({ employeeId: b.employeeId, duration: b.duration })),
        };

        const res = await fetch(`/api/timeslots/multi`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        });
        const slots = await res.json();

        timeSelect.innerHTML = "";
        if (!slots || slots.length === 0) {
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
    }

    async function init() {
        const shopId = getShopId();
        if (!shopId || !blocksRoot) return;

        populateDateOptions();

        // Render all employees as cards
        blocksRoot.innerHTML = "";
        const employees = await fetchEmployees(shopId);
        employees.forEach(emp => {
            blocksRoot.appendChild(createEmployeeCard(emp));
        });

        // Once rendered, set up date listener and fetch initial (empty) timeslots state
        dateSelect?.addEventListener("change", () => {
            fetchTimeSlots().catch(console.error);
        });

        // Render initial hint in timeslots
        await fetchTimeSlots();

        bookingForm?.addEventListener("submit", (e) => {
            const blocks = getSelectedBlocks();
            if (blocks.length === 0) {
                e.preventDefault();
                alert("Please select at least one service.");
                return;
            }

            const payload = {
                appointmentTime: timeSelect.value,
                date: dateSelect.value,
                blocks: blocks.map(b => ({ employeeId: b.employeeId, serviceIds: b.serviceIds })),
            };
            if (payloadInput) payloadInput.value = JSON.stringify(payload);
        });
    }

    init().catch(err => console.error("[BookingUI] init failed:", err));
});
