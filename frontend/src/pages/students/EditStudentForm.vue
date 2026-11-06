<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <!-- An active student is deactivated only through the Deactivate action below, which warns and confirms
           (UC-STU-deactivate-student); a deactivated student keeps the existing enable path -->
      <el-tag v-if="initiallyEnabled" type="success" data-cy="student-status">Active</el-tag>
      <el-switch
        v-else
        v-model="studentBeingEdited.enabled"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
        data-cy="student-enable-switch"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: space-between">
      <el-button
        type="danger"
        plain
        :disabled="!loaded"
        :loading="deactivating"
        @click="confirmDeactivation()"
        data-cy="deactivate-student"
      >
        Deactivate
      </el-button>
      <div>
        <el-button @click="$emit('close-dialog')"> Cancel </el-button>
        <el-button type="primary" @click="updateExistingStudent()"> Confirm </el-button>
      </div>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { deactivateStudent, findStudentById, updateStudent } from '@/apis/student'
import { h, ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog'])

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference
const loaded = ref(false) // The persisted student has been read, so the action has a target
const initiallyEnabled = ref(false) // The persisted account state, not the form's draft
const persistedName = ref('') // The persisted name, which the warning uses whatever the form currently says
const deactivating = ref(false)

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  enabled: [{ required: true, message: 'Please select the status of the user.', trigger: 'blur' }],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
  initiallyEnabled.value = result.data.enabled === true
  persistedName.value = `${result.data.firstName} ${result.data.lastName}`
  loaded.value = true
}

onMounted(() => {
  loadStudent(studentId) // Load the artifact to be edited
})

async function updateExistingStudent() {
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    enabled: studentBeingEdited.value.enabled,
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}

// The consequences of BR-student-lifecycle, BR-student-deactivation-team, BR-submission-owed,
// BR-evaluation-active-evaluatee, BR-evaluation-inactive-report and FR-SEC-active-account, in that order
function deactivationConsequences(name: string): string[] {
  return [
    `${name} can no longer log in, and any session already open ends on its next request.`,
    `${name}'s weekly activity reports and peer evaluations are kept.`,
    `${name} stays on the current team.`,
    `${name} no longer owes weekly activity reports or peer evaluations, and teammates no longer owe an evaluation of ${name}.`,
    `Teammates can no longer submit or edit a peer evaluation of ${name}.`,
    `${name} stays in peer evaluation reports, labeled Inactive.`
  ]
}

// UC-STU-deactivate-student steps 3-6. Cancel, the close icon and Escape send nothing (extension 4a). Confirm keeps
// the box open with a busy button until the response, so a second click cannot send a second request.
async function confirmDeactivation() {
  const targetId = studentBeingEdited.value.id as number // The persisted target; profile drafts are not saved
  const name = persistedName.value
  try {
    await ElMessageBox.confirm(
      h('div', { 'data-cy': 'deactivate-warning' }, [
        h('p', `Deactivate ${name}? This takes effect immediately:`),
        h(
          'ul',
          deactivationConsequences(name).map((consequence) => h('li', consequence))
        )
      ]),
      'Deactivate student',
      {
        type: 'warning',
        confirmButtonText: 'Deactivate',
        cancelButtonText: 'Cancel',
        confirmButtonClass: 'deactivate-confirm',
        cancelButtonClass: 'deactivate-cancel',
        beforeClose: async (action, instance, done) => {
          if (action !== 'confirm') {
            if (!deactivating.value) done()
            return
          }
          if (deactivating.value) return
          deactivating.value = true
          instance.confirmButtonLoading = true
          try {
            const result = await deactivateStudent(targetId)
            ElMessage.success(result.message) // Deactivated now, or already deactivated (6b)
            done()
            emit('close-dialog') // Closes the editor and refreshes the list
          } catch {
            // The shared request interceptor has already shown the error (or redirected)
            done()
          } finally {
            instance.confirmButtonLoading = false
            deactivating.value = false
          }
        }
      }
    )
  } catch {
    // Cancelled or closed: no request was sent (extension 4a)
  }
}
</script>

<style scoped lang="scss"></style>
