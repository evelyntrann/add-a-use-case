// UC-STU-deactivate-student: the editor's Deactivate action. The student API calls are stubbed, so these tests check
// what the UI shows and what it sends; the backend's own tests cover who may deactivate and what changes.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const page = (students) => ({
  flag: true,
  code: 200,
  message: 'Find students successfully',
  data: { content: students, number: 0, size: 50, totalElements: students.length }
})

const john = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  teamName: 'Team1'
}
const inactiveJohn = { ...john, enabled: false }

const consequences = [
  'John Smith can no longer log in, and any session already open ends on its next request.',
  "John Smith's weekly activity reports and peer evaluations are kept.",
  'John Smith stays on the current team.',
  'John Smith no longer owes weekly activity reports or peer evaluations, and teammates no longer owe an evaluation of John Smith.',
  'Teammates can no longer submit or edit a peer evaluation of John Smith.',
  'John Smith stays in peer evaluation reports, labeled Inactive.'
]

describe('Deactivate a student', () => {
  let deactivationRequests

  // The list is answered by `before` until a deactivation succeeds, then by `after`
  function stubStudents(before, after, deactivationMessage) {
    deactivationRequests = 0
    let deactivated = false
    cy.intercept('POST', '**/students/search*', (req) =>
      req.reply(page(deactivated ? [after] : [before]))
    ).as('search')
    cy.intercept('GET', '**/students/4', (req) =>
      req.reply({ flag: true, code: 200, message: 'Find student successfully', data: before })
    ).as('student')
    cy.intercept('POST', '**/students/4/deactivation', (req) => {
      deactivationRequests += 1
      deactivated = true
      req.reply({
        delay: 800, // Long enough to click Deactivate again while the request is in flight
        body: { flag: true, code: 200, message: deactivationMessage, data: null }
      })
    }).as('deactivation')
  }

  function openEditor() {
    cy.visit('/students')
    cy.wait('@search')
    cy.get('.el-table__row').first().find('button').click()
    cy.wait('@student')
  }

  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
  })

  it('warns with every consequence and sends nothing on cancel, close or Escape (steps 3-4, 4a)', () => {
    stubStudents(john, { ...john, enabled: false }, 'Student deactivated successfully')
    openEditor()
    cy.get('[data-cy=student-status]').should('have.text', 'Active') // No status switch for an active student

    // Cancel
    cy.get('[data-cy=deactivate-student]').click()
    consequences.forEach((text) => cy.get('[data-cy=deactivate-warning]').should('contain', text))
    cy.get('.deactivate-cancel').click()
    cy.get('[data-cy=deactivate-warning]').should('not.exist')

    // Close icon
    cy.get('[data-cy=deactivate-student]').click()
    cy.get('.el-message-box__headerbtn').click()
    cy.get('[data-cy=deactivate-warning]').should('not.exist')

    // Escape
    cy.get('[data-cy=deactivate-student]').click()
    cy.get('[data-cy=deactivate-warning]').should('be.visible')
    cy.get('body').type('{esc}')
    cy.get('[data-cy=deactivate-warning]').should('not.exist')

    cy.wait(500).then(() => expect(deactivationRequests).to.equal(0))
  })

  it('sends one request on confirm, shows the result and the refreshed inactive status (steps 5-6)', () => {
    stubStudents(john, { ...john, enabled: false }, 'Student deactivated successfully')
    openEditor()
    cy.get('[data-cy=deactivate-student]').click()
    cy.get('.deactivate-confirm').click()
    cy.get('.deactivate-confirm').click({ force: true }) // A second click while busy is ignored

    cy.wait('@deactivation').its('request.body').should('be.oneOf', [undefined, null, ''])
    cy.contains('.el-message', 'Student deactivated successfully')
    cy.get('.el-dialog').should('not.be.visible') // Editor closed
    cy.wait('@search')
    cy.get('.el-table__row').first().should('contain', 'Disabled')
    cy.then(() => expect(deactivationRequests).to.equal(1))
  })

  it('still offers Deactivate for an inactive student, with the already-deactivated result (PRE-2, 6b)', () => {
    // Every student on the roster is already inactive
    stubStudents(inactiveJohn, inactiveJohn, 'Student is already deactivated')
    openEditor()
    cy.get('[data-cy=student-enable-switch]').should('exist') // The existing enable path is kept
    cy.get('[data-cy=deactivate-student]').should('be.enabled').click()
    cy.get('.deactivate-confirm').click()

    cy.wait('@deactivation')
    cy.contains('.el-message', 'Student is already deactivated')
    cy.wait('@search')
    cy.get('.el-table__row').first().should('contain', 'Disabled')
  })
})
